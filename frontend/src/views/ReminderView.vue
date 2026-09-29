<script setup>
/**
 * 提醒任务中心。
 *
 * 版面按「这是关于时间的工具」来组织：
 *   顶部  一条信息带 —— 接下来什么时候会发出邮件；
 *   左栏  待提交作业快捷清单 —— 点一下就按「提前 N 天」把提醒建好，不用进表单；
 *   右栏  时间轴 —— 按日期分组，每条左边是发送时刻；
 *   弹窗  完整编辑器 —— 选作业 / 自己写、快捷提前天数、重复规则。
 * 没有占位符：发出去的就是你写的内容。收件人固定为账号邮箱。
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ApiError } from '../api/http';
import {
    isRepeatRule, nextSendDate, reminderApi, repeatText, sendTimeText, statusInfo, untilText,
} from '../api/reminder';
import { homeworkSummary, loadHomeworkSummary } from '../stores/homeworkSummary';
import { session } from '../stores/session';
import { icons } from '../ui/icons';
import AppDateTime from '../ui/AppDateTime.vue';
import AppSelect from '../ui/AppSelect.vue';
import { toast } from '../ui/toast';

const WEEKDAYS = [
    { value: 1, label: '一' }, { value: 2, label: '二' }, { value: 3, label: '三' },
    { value: 4, label: '四' }, { value: 5, label: '五' }, { value: 6, label: '六' },
    { value: 7, label: '日' },
];

const REPEAT_OPTIONS = [
    { value: 'none', label: '仅一次' },
    { value: 'daily', label: '每天' },
    { value: 'weekly', label: '每周' },
    { value: 'monthly', label: '每月' },
    { value: 'immediate', label: '立即发送' },
];

/** 「提前几天」可选范围：1–7 天 */
const LEAD_DAYS = [1, 2, 3, 4, 5, 6, 7];

/** 自绘下拉用的选项表 */
const DAY_OPTIONS = LEAD_DAYS.map((n) => ({ value: n, label: String(n) }));
const STATUS_OPTIONS = [
    { value: '', label: '全部状态' },
    { value: '0', label: '待发送' },
    { value: '1', label: '已发送' },
    { value: '2', label: '已禁用' },
    { value: '3', label: '发送失败' },
];

const email = computed(() => session.user?.email || '');

function pad(value) {
    return String(value).padStart(2, '0');
}

function dateText(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function clockText(date) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function timeText(date) {
    return `${dateText(date)} ${clockText(date)}`;
}

/** 后端给的是 yyyy-MM-dd HH:mm:ss */
function toDate(text) {
    if (!text) return null;
    const date = new Date(String(text).replace(' ', 'T'));
    return Number.isNaN(date.getTime()) ? null : date;
}

/** 日期分组标题：今天 / 明天 / 后天 / 10 月 8 日 周三 */
function dateLabel(dateStr) {
    if (!dateStr) return '未定时间';
    const target = toDate(`${dateStr} 00:00:00`);
    if (!target) return dateStr;
    const today = new Date();
    const dayStart = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    const diff = Math.round((dayStart(target) - dayStart(today)) / 86400000);
    const week = '日一二三四五六'[target.getDay()];
    if (diff === 0) return '今天';
    if (diff === 1) return '明天';
    if (diff === 2) return '后天';
    return `${target.getMonth() + 1} 月 ${target.getDate()} 日 周${week}`;
}

// ==================== 页面级错误 ====================

const pageError = ref('');

function describeError(err) {
    if (err instanceof ApiError) {
        if (err.status === 404) {
            return '后端没有这个接口（/api/task）。提醒功能是这轮新加的，请先重启 Java 应用（Student 模块）——旧进程里没有它。';
        }
        if (err.status === 401) {
            return '登录状态失效，或后端仍是旧版本。请重启 Java 应用（Student）后重新登录再试。';
        }
        if (err.status === 500) return `服务端出错：${err.message}`;
        return err.message;
    }
    return '加载失败，请稍后重试。';
}

// ==================== 概览 + 时间轴 ====================

const loading = ref(true);
const tasks = ref([]);
const total = ref(0);
const page = ref(1);
const limit = ref(10);
const search = ref('');
const statusFilter = ref('');
const allUsers = ref(false);
const includeDeleted = ref(false);
const isAdmin = ref(false);
const busyId = ref(null);
const stats = ref(null);

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / limit.value)));

/** 时间轴按日期分组。后端已按 scheduledTime 正序返回（第一页就是接下来要发的），
    这里只负责分组，组内顺序即后端顺序。 */
const groups = computed(() => {
    const map = new Map();
    for (const task of tasks.value) {
        const day = String(task.scheduledTime || '').slice(0, 10);
        if (!map.has(day)) map.set(day, { day, label: dateLabel(day), items: [] });
        map.get(day).items.push(task);
    }
    return [...map.values()];
});

/** 顶部那句「接下来」 */
const nextText = computed(() => {
    const next = stats.value?.nextScheduledTime;
    if (!next) return '还没有待发送的提醒';
    const when = toDate(next);
    const clock = clockText(when);
    const day = dateLabel(String(next).slice(0, 10));
    if (['今天', '明天', '后天'].includes(day)) return `${day} ${clock} 发出下一封`;
    return `${day} ${clock} 发出下一封`;
});

async function loadStats(silent = false) {
    try {
        stats.value = await reminderApi.stats({ allUsers: allUsers.value ? 'true' : '' });
    } catch (err) {
        // 静默轮询失败时保留上一次的概览，别把顶部信息清空
        if (!silent) stats.value = null;
    }
}

/** 静默轮询：内容没变就不动 DOM，避免每 5 秒重排一次 */
function sameAsCurrent(next) {
    return tasks.value.length === next.length && JSON.stringify(next) === JSON.stringify(tasks.value);
}

/**
 * 拉取列表。
 *
 * silent = true 用于自动轮询：不发「加载中」、失败不清空、数据没变化不赋值 ——
 * 也就是照旧请求接口，但界面不闪、不跳、不重置滚动位置。
 */
async function loadTasks({ silent = false } = {}) {
    if (!silent) loading.value = true;
    try {
        const data = await reminderApi.list({
            page: page.value,
            limit: limit.value,
            search: search.value.trim(),
            status: statusFilter.value,
            allUsers: allUsers.value ? 'true' : '',
            includeDeleted: includeDeleted.value ? 'true' : '',
        });
        const next = data.tasks || [];
        if (!silent || !sameAsCurrent(next)) {
            tasks.value = next;
        }
        total.value = data.total || 0;
        isAdmin.value = Boolean(data.isAdmin);
        pageError.value = '';
        loadStats(silent);
    } catch (err) {
        if (silent) return;      // 轮询失败不打扰用户，下一轮会再试
        pageError.value = describeError(err);
        tasks.value = [];
        total.value = 0;
    } finally {
        if (!silent) loading.value = false;
    }
}

function applyFilters() {
    page.value = 1;
    loadTasks();
}

function resetFilters() {
    search.value = '';
    statusFilter.value = '';
    allUsers.value = false;
    includeDeleted.value = false;
    applyFilters();
}

function goPage(delta) {
    const next = page.value + delta;
    if (next < 1 || next > totalPages.value) return;
    page.value = next;
    loadTasks();
}

// 自动刷新：页面隐藏时暂停
const AUTO_REFRESH_SECONDS = 5;
const countdown = ref(AUTO_REFRESH_SECONDS);
let autoTimer = null;

function tick() {
    countdown.value -= 1;
    if (countdown.value <= 0) {
        countdown.value = AUTO_REFRESH_SECONDS;
        loadTasks({ silent: true });
    }
}

function startAutoRefresh() {
    stopAutoRefresh();
    autoTimer = setInterval(tick, 1000);
}

function stopAutoRefresh() {
    if (autoTimer) clearInterval(autoTimer);
    autoTimer = null;
}

function onVisibilityChange() {
    if (document.hidden) {
        stopAutoRefresh();
    } else {
        countdown.value = AUTO_REFRESH_SECONDS;
        startAutoRefresh();
        loadTasks({ silent: true });
    }
}

// ==================== 待提交作业（快捷创建） ====================

const jobQuery = ref('');

const jobs = computed(() => {
    const keyword = jobQuery.value.trim().toLowerCase();
    const list = homeworkSummary.jobs || [];
    if (!keyword) return list;
    return list.filter((job) => `${job.title} ${job.courseName} ${job.platformText}`
        .toLowerCase().includes(keyword));
});

function escapeHtml(text) {
    return String(text ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/**
 * 纯文本版提醒正文 —— 带出作业时默认用这一版。
 * 纯文本在任何客户端都不会乱版，也最容易被顺手改两句；想要好看再切 HTML。
 */
function textBodyOf(job) {
    const lines = [`【${job.platformText}】${job.title}`];
    if (job.courseName) lines.push(`课程：${job.courseName}`);
    if (job.dueDay) lines.push(`截止：${job.dueDay} ${job.dueClock}`);
    lines.push('', '记得按时提交，交完就不用再惦记它了。');
    return lines.join('\n');
}

/**
 * 预览用的完整文档：补一段滚动条样式。
 * iframe 里的滚动条属于它自己的文档，外面的 CSS 管不到，只能塞进去。
 */
function previewDoc(html) {
    const scrollCss = '<style>'
        + '::-webkit-scrollbar{width:8px;height:8px}'
        + '::-webkit-scrollbar-thumb{background:rgba(176,137,90,.42);border-radius:8px}'
        + '::-webkit-scrollbar-thumb:hover{background:rgba(176,137,90,.65)}'
        + '::-webkit-scrollbar-track{background:transparent}'
        + '::-webkit-scrollbar-button{display:none;width:0;height:0}'
        + 'html{scrollbar-width:thin;scrollbar-color:rgba(176,137,90,.42) transparent}'
        + '</style>';
    return scrollCss
        + (html || '<p style="color:#8b8178;font-family:sans-serif">还没有内容</p>');
}

/**
 * 从作业生成提醒邮件的 HTML 正文。
 *
 * 邮件客户端对 CSS 支持很有限，所以按邮件规范写：全部内联样式 + table 布局，
 * 不用 flex / position，不引外链图片（QQ 邮箱会拦掉），配色沿用站内的暖金体系。
 * 生成后用户仍可自由编辑（编辑框就是 HTML，右侧能实时预览）。
 */
function htmlBodyOf(job) {
    const course = job.courseName || job.platformText;
    const deadline = job.dueDay ? `${job.dueDay} ${job.dueClock}` : '未设置截止时间';
    return `<div style="margin:0;padding:0;background-color:#f6f1e8;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f6f1e8;">
    <tr>
      <td align="center" style="padding:32px 14px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
               style="max-width:560px;background-color:#ffffff;border-radius:20px;overflow:hidden;">
          <tr>
            <td style="padding:26px 28px;background-color:#b0895a;background-image:linear-gradient(135deg,#a97e4e,#c9a170 55%,#e3c497);text-align:center;">
              <p style="margin:0;font-size:12px;letter-spacing:2px;color:#f6ece0;">作业提醒</p>
              <h1 style="margin:8px 0 0;font-size:21px;font-weight:700;line-height:1.5;color:#ffffff;">${escapeHtml(job.title)}</h1>
            </td>
          </tr>
          <tr>
            <td style="padding:22px 28px 6px;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr>
                  <td style="padding:8px 0;width:64px;font-size:13px;color:#8b8178;">课程</td>
                  <td style="padding:8px 0;font-size:14px;color:#3d352c;">${escapeHtml(course)}</td>
                </tr>
                <tr>
                  <td style="padding:8px 0;font-size:13px;color:#8b8178;">截止</td>
                  <td style="padding:8px 0;font-size:16px;font-weight:700;color:#b1564f;">${escapeHtml(deadline)}</td>
                </tr>
                <tr>
                  <td style="padding:8px 0;font-size:13px;color:#8b8178;">来源</td>
                  <td style="padding:8px 0;font-size:14px;color:#3d352c;">${escapeHtml(job.platformText)}</td>
                </tr>
              </table>
            </td>
          </tr>
          <tr>
            <td style="padding:4px 28px 24px;">
              <p style="margin:0;font-size:14px;line-height:1.8;color:#5a5148;">别忘了按时提交，交完就不用再惦记它了。</p>
            </td>
          </tr>
          <tr>
            <td style="padding:14px 28px 20px;border-top:1px solid #efe7db;text-align:center;">
              <p style="margin:0;font-size:11.5px;color:#a89e93;">本邮件由系统自动发送，请勿直接回复</p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</div>`;
}

const DAY_MS = 86400000;

/** 每条作业记住用户选的天数（默认提前 1 天） */
const jobDays = reactive({});

function daysOf(job) {
    const value = Number(jobDays[job.key]);
    return value >= 1 && value <= 7 ? value : 1;
}

function setDays(job, value) {
    jobDays[job.key] = Math.min(7, Math.max(1, Number(value) || 1));
}

/** 「提前 N 天」对应的发送时间：有截止时间就从截止往前推，没有就按「N 天后」提醒 */
function fireTimeOf(job, days) {
    if (job?.endTime) return new Date(job.endTime - days * DAY_MS);
    return new Date(Date.now() + days * DAY_MS);
}

/** 算出来的时间已经过去时（例如截止就在眼前），退到 5 分钟后 */
function resolveFireTime(job, days) {
    const target = fireTimeOf(job, days);
    return target.getTime() > Date.now() ? target : new Date(Date.now() + 5 * 60000);
}

// ---- 确认弹窗：选完天数先确认，再真正创建 ----
const confirmBox = reactive({ open: false, job: null, days: 1, clamped: false });
const creatingQuick = ref(false);

function askQuick(job) {
    confirmBox.open = true;
    confirmBox.job = job;
    confirmBox.days = daysOf(job);
    refreshConfirm();
}

/** 天数变化时重算，并标记「时间已过、会改成 5 分钟后」 */
function refreshConfirm() {
    confirmBox.days = Math.min(7, Math.max(1, Number(confirmBox.days) || 1));
    const target = fireTimeOf(confirmBox.job, confirmBox.days);
    confirmBox.clamped = target.getTime() <= Date.now();
}

const confirmTimeText = computed(() => {
    if (!confirmBox.job) return '';
    return timeText(resolveFireTime(confirmBox.job, confirmBox.days));
});

async function confirmQuickCreate() {
    if (creatingQuick.value || !confirmBox.job || !email.value) return;
    creatingQuick.value = true;
    try {
        const job = confirmBox.job;
        setDays(job, confirmBox.days);
        await reminderApi.create({
            taskData: {
                subject: `⏰ 作业提醒：${job.title}`,
                content: textBodyOf(job),
                format: 'text',
                repeat: 'none',
                repeat_data: { days: [], day: null },
                category: 'homework',
            },
            scheduledTime: `${confirmTimeText.value}:00`,
        });
        toast(`已建好：提前 ${confirmBox.days} 天提醒`, 'success');
        confirmBox.open = false;
        page.value = 1;
        await loadTasks();
    } catch (err) {
        toast(describeError(err), 'error', 4000);
    } finally {
        creatingQuick.value = false;
    }
}

// ==================== 编辑器弹窗 ====================

const editor = reactive({
    open: false,
    mode: 'import',
    job: null,
    subject: '',
    content: '',
    /** 带出来的两套模板（纯文本 / HTML），切换格式时按需替换 */
    suggested: { text: '', html: '' },
    format: 'text',
    repeat: 'none',
    weeklyDays: [],
    monthlyDay: 1,
    date: dateText(new Date()),
    time: '08:00',
    lead: 1,
    useLead: true,
});
const saving = ref(false);

/** HTML 正文的实时预览：默认展开，切回纯文本时整块隐藏 */
const showEditorPreview = ref(true);
const editorPreviewDoc = computed(() => previewDoc(editor.content));

function openEditor(mode, job = null) {
    editor.open = true;
    editor.mode = mode;
    editor.lead = 1;
    editor.useLead = true;
    editor.repeat = 'none';
    editor.weeklyDays = [];
    editor.monthlyDay = 1;
    editor.format = 'text';
    if (job) {
        pickJob(job);
    } else {
        editor.job = null;
        editor.subject = '';
        editor.content = '';
        editor.suggested = { text: '', html: '' };
        const base = new Date(Date.now() + 3600000);
        editor.date = dateText(base);
        editor.time = clockText(base);
    }
}

function pickJob(job) {
    editor.job = job;
    editor.subject = `⏰ 作业提醒：${job.title}`;
    // 两套模板都备好：纯文本（默认）与 HTML，切换格式时用对应的那套
    editor.suggested = { text: textBodyOf(job), html: htmlBodyOf(job) };
    editor.format = 'text';
    editor.content = editor.suggested.text;
    applyLead(editor.lead);
}

/**
 * 切换纯文本 / HTML。
 * 正文若还是「带出来的那一版原样模板」就换成对应版本；用户已经改过内容则一个字都不动，
 * 免得切一下格式就把自己写的东西冲掉。
 */
function switchFormat(next) {
    if (next === editor.format) return;
    const current = (editor.content || '').trim();
    const untouched = !current || current === (editor.suggested?.[editor.format] || '').trim();
    editor.format = next;
    if (untouched && editor.suggested?.[next]) {
        editor.content = editor.suggested[next];
    }
}

/** 快捷「提前 N 天」：把发送时间算成 截止 - N 天 */
function applyLead(daysBefore) {
    editor.lead = daysBefore;
    if (!editor.job?.endTime) {
        editor.useLead = false;
        return;
    }
    const target = fireTimeOf(editor.job, daysBefore);
    editor.date = dateText(target);
    editor.time = clockText(target);
    editor.useLead = true;
}

const leadPreview = computed(() => {
    if (!editor.useLead || !editor.job?.endTime) return '';
    return `${editor.date} ${editor.time} 发出（截止 ${editor.job.dueDay} ${editor.job.dueClock}）`;
});

function toggleWeekday(value) {
    const index = editor.weeklyDays.indexOf(value);
    if (index >= 0) editor.weeklyDays.splice(index, 1);
    else editor.weeklyDays.push(value);
}

const isImmediate = computed(() => editor.repeat === 'immediate');

/** 重复规则的「哪一天」由规则自己决定，界面上就不该再要一个具体日期 */
const editorRepeats = computed(() => isRepeatRule(editor.repeat));
const editorNeedsDate = computed(() => !editorRepeats.value && !isImmediate.value);
/** 规则 + 时刻 → 下次发送（给用户看的预览，算法与后端一致） */
const editorNextSend = computed(() => sendTimeText(nextSendDate(editor.repeat,
    { days: editor.weeklyDays, day: editor.monthlyDay }, editor.time)));

/**
 * 组装提交用的 scheduledTime。
 * 立即发送：必须给「当前时刻」并带秒（后端只认 yyyy-MM-dd HH:mm:ss），
 * 否则界面上没有时间可选，拿别处的值会得到一个过去时间被拒。
 * 重复规则：日期只是「不早于此」的起点，统一用今天，后端会推进到第一个符合规则的时间点。
 */
function scheduleText(date, time, repeat) {
    if (repeat === 'immediate') {
        const now = new Date();
        return `${dateText(now)} ${clockText(now)}:${pad(now.getSeconds())}`;
    }
    return `${repeat === 'none' ? date : dateText(new Date())} ${time}:00`;
}

async function submitEditor() {
    if (saving.value) return;
    if (editor.mode === 'import' && !editor.job) {
        toast('先选一条作业', 'error');
        return;
    }
    if (!editor.subject.trim() || !editor.content.trim()) {
        toast('主题和正文都要填', 'error');
        return;
    }
    if (editor.repeat === 'weekly' && !editor.weeklyDays.length) {
        toast('每周重复至少选一天', 'error');
        return;
    }
    saving.value = true;
    try {
        const when = scheduleText(editor.date, editor.time, editor.repeat);
        const data = await reminderApi.create({
            taskData: {
                subject: editor.subject.trim(),
                content: editor.content,
                format: editor.format,
                repeat: editor.repeat,
                repeat_data: {
                    days: editor.repeat === 'weekly' ? [...editor.weeklyDays].sort((a, b) => a - b) : [],
                    day: editor.repeat === 'monthly' ? Number(editor.monthlyDay) : null,
                },
                category: editor.mode === 'import' ? 'homework' : 'custom',
            },
            scheduledTime: when,
        });
        toast(`已创建提醒（#${data?.task_id ?? ''}）`, 'success');
        editor.open = false;
        page.value = 1;
        await loadTasks();
    } catch (err) {
        toast(describeError(err), 'error', 4000);
    } finally {
        saving.value = false;
    }
}

// ==================== 行内操作 ====================

async function run(task, action, doneText) {
    if (busyId.value) return;
    busyId.value = task.id;
    try {
        const message = await action(task.id);
        toast(message || doneText, 'success');
        if (detail.value?.task?.id === task.id) await openDetail(task.id);
        await loadTasks();
    } catch (err) {
        toast(describeError(err), 'error', 4000);
    } finally {
        busyId.value = null;
    }
}

const executeTask = (task) => run(task, reminderApi.execute, '已发送');
const cancelTask = (task) => run(task, reminderApi.cancel, '已禁用');
const enableTask = (task) => run(task, reminderApi.enable, '已启用');
const restoreTask = (task) => run(task, reminderApi.restore, '已恢复');

async function removeTask(task) {
    if (!window.confirm(`确认删除「${subjectOf(task)}」？`)) return;
    await run(task, reminderApi.remove, '已删除');
}

// ==================== 详情 / 改规则 ====================

const detail = ref(null);
const detailLoading = ref(false);

async function openDetail(id) {
    detailLoading.value = true;
    try {
        detail.value = await reminderApi.detail(id);
    } catch (err) {
        toast(describeError(err), 'error', 4000);
    } finally {
        detailLoading.value = false;
    }
}

const edit = reactive({
    open: false, taskId: null, date: '', time: '08:00',
    repeat: 'none', weeklyDays: [], monthlyDay: 1,
});
const savingEdit = ref(false);

function openEdit(task) {
    const data = task.taskData || {};
    const target = toDate(task.scheduledTime) || new Date(Date.now() + 3600000);
    edit.open = true;
    edit.taskId = task.id;
    edit.repeat = data.repeat || 'none';
    edit.weeklyDays = [...(data.repeat_data?.days || [])];
    edit.monthlyDay = data.repeat_data?.day || 1;
    edit.date = dateText(target);
    edit.time = clockText(target);
}

function toggleEditWeekday(value) {
    const index = edit.weeklyDays.indexOf(value);
    if (index >= 0) edit.weeklyDays.splice(index, 1);
    else edit.weeklyDays.push(value);
}

/** 同编辑器：重复规则下只挑时刻，不再挑日期 */
const editRepeats = computed(() => isRepeatRule(edit.repeat));
const editNeedsDate = computed(() => !editRepeats.value && edit.repeat !== 'immediate');
const editNextSend = computed(() => sendTimeText(nextSendDate(edit.repeat,
    { days: edit.weeklyDays, day: edit.monthlyDay }, edit.time)));

async function submitEdit() {
    if (savingEdit.value) return;
    if (edit.repeat === 'weekly' && !edit.weeklyDays.length) {
        toast('每周重复至少选一天', 'error');
        return;
    }
    savingEdit.value = true;
    try {
        const message = await reminderApi.update({
            taskId: edit.taskId,
            scheduledTime: scheduleText(edit.date, edit.time, edit.repeat),
            repeat: edit.repeat,
            repeatData: {
                days: edit.repeat === 'weekly' ? [...edit.weeklyDays].sort((a, b) => a - b) : [],
                day: edit.repeat === 'monthly' ? Number(edit.monthlyDay) : null,
            },
        });
        toast(message || '已更新发送规则', 'success');
        edit.open = false;
        await loadTasks();
    } catch (err) {
        toast(describeError(err), 'error', 4000);
    } finally {
        savingEdit.value = false;
    }
}

// ==================== 展示辅助 ====================

function subjectOf(task) {
    return String(task.taskData?.subject || '未命名任务');
}

function snippetOf(task) {
    const text = String(task.taskData?.content || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    return text.length > 80 ? `${text.slice(0, 80)}…` : text;
}

function canExecute(task) {
    return task.status === 0 || task.status === 1 || task.status === 3;
}

function statusStripe(task) {
    if (task.isDeleted === 1) return 'deleted';
    return statusInfo(task.status).cls;
}

onMounted(async () => {
    loadHomeworkSummary();
    await loadTasks();
    startAutoRefresh();
    document.addEventListener('visibilitychange', onVisibilityChange);
});

onUnmounted(() => {
    stopAutoRefresh();
    document.removeEventListener('visibilitychange', onVisibilityChange);
});
</script>

<template>
    <div class="page">
        <!-- ===== 顶部：接下来会发生什么 ===== -->
        <header class="rmo-head">
            <div class="rmo-head-main">
                <h1>提醒任务</h1>
                <p class="rmo-next">
                    {{ nextText }}
                    <span v-if="stats" class="rmo-tally">
                        {{ stats.pending }} 待发送<template v-if="stats.failed"> · {{ stats.failed }} 失败</template><template
                        v-if="stats.disabled"> · {{ stats.disabled }} 已禁用</template>
                    </span>
                </p>
            </div>
            <div class="rmo-head-side">
                <span class="rmo-to">收件人 {{ email || '（账号未设置邮箱）' }}</span>
                <span class="rmo-tick">{{ countdown }}s 后同步</span>
            </div>
        </header>

        <div v-if="pageError" class="rmo-error">
            <span v-html="icons.info"></span>
            <div>
                <strong>没能加载</strong>
                <p>{{ pageError }}</p>
            </div>
            <button type="button" class="btn inline ghost" @click="loadTasks">重试</button>
        </div>

        <div class="rmo-grid">
            <!-- ===== 左：待提交作业，一键建提醒 ===== -->
            <aside class="rmo-side">
                <div class="rmo-side-head">
                    <h2>待提交作业<span v-if="jobs.length" class="rmo-count">{{ jobs.length }}</span></h2>
                    <button type="button" class="rmo-link" @click="openEditor('custom')">自己写一封</button>
                </div>

                <input v-model="jobQuery" type="search" class="rmo-input" aria-label="搜索待提交作业"
                       placeholder="搜索作业 / 课程" />

                <div v-if="!jobs.length" class="rmo-empty">
                    <span v-html="icons.info"></span>
                    <span>
                        没有待提交的作业。
                        <RouterLink class="link" to="/homework">去作业查询刷新</RouterLink>
                    </span>
                </div>

                <ul v-else class="rmo-jobs">
                    <li v-for="job in jobs" :key="job.key" class="rmo-job">
                        <div class="rmo-job-top">
                            <span class="rmo-plat" :class="job.platform">{{ job.platformText }}</span>
                            <strong>{{ job.title }}</strong>
                        </div>
                        <div class="rmo-job-due">
                            <span v-if="job.dueDay">截止 {{ job.dueDay }} {{ job.dueClock }}</span>
                            <span v-else>无截止时间</span>
                        </div>
                        <div class="rmo-job-act">
                            <span class="rmo-lead-word">提前</span>
                            <AppSelect compact :model-value="daysOf(job)" :options="DAY_OPTIONS"
                                       :aria-label="`提前几天提醒：${job.title}`"
                                       @change="setDays(job, $event)" />
                            <span class="rmo-lead-word">天</span>
                            <button type="button" class="rmo-chip primary" @click="askQuick(job)">建提醒</button>
                            <button type="button" class="rmo-chip ghost" @click="openEditor('import', job)">
                                详细设置
                            </button>
                        </div>
                    </li>
                </ul>
            </aside>

            <!-- ===== 右：时间轴 ===== -->
            <section class="rmo-main">
                <div class="rmo-toolbar">
                    <AppSelect v-model="statusFilter" class="rmo-sel" :options="STATUS_OPTIONS"
                               aria-label="按状态筛选" @change="applyFilters" />
                    <input v-model="search" type="search" class="rmo-input rmo-mini" aria-label="搜索提醒主题"
                           placeholder="搜索主题" @keyup.enter="applyFilters" />
                    <button type="button" class="rmo-chip ghost" @click="applyFilters">筛选</button>
                    <button type="button" class="rmo-chip ghost" @click="resetFilters">重置</button>
                    <button type="button" class="rmo-chip ghost" @click="loadTasks()">刷新</button>
                    <span class="rmo-grow"></span>
                    <button type="button" class="rmo-chip primary" @click="openEditor('import')">新建提醒</button>
                </div>

                <div v-if="isAdmin" class="rmo-admin">
                    <label><input type="checkbox" v-model="allUsers" @change="applyFilters" /> 查看所有人</label>
                    <label><input type="checkbox" v-model="includeDeleted" @change="applyFilters" /> 查看已删除</label>
                </div>

                <div v-if="loading" class="rmo-empty"><span class="spinner"></span><span>正在加载…</span></div>

                <div v-else-if="!tasks.length" class="rmo-empty rmo-empty-big">
                    <span v-html="icons.info"></span>
                    <div>
                        <strong>还没有提醒</strong>
                        <p>左边点一下作业右边的「提前 1 天」，几秒钟就能建好第一条。</p>
                    </div>
                </div>

                <template v-else>
                    <div v-for="group in groups" :key="group.day" class="rmo-day">
                        <div class="rmo-day-label">
                            {{ group.label }}
                            <span class="rmo-day-n">{{ group.items.length }}</span>
                        </div>
                        <ol class="rmo-line">
                            <li v-for="task in group.items" :key="task.id" class="rmo-entry"
                                :class="`st-${statusStripe(task)}`">
                                <div class="rmo-stamp">
                                    <span class="rmo-time">{{ String(task.scheduledTime || '').slice(11, 16) }}</span>
                                    <span class="rmo-rel">{{ untilText(task.scheduledTime) }}</span>
                                </div>
                                <div class="rmo-body">
                                    <div class="rmo-title">
                                        <span class="rmo-state" :class="statusInfo(task.status).cls">
                                            {{ statusInfo(task.status).text }}
                                        </span>
                                        <span v-if="task.isDeleted === 1" class="rmo-state deleted">已删除</span>
                                        <span v-if="task.taskData?.format === 'html'" class="rmo-state html">HTML</span>
                                        <strong>{{ subjectOf(task) }}</strong>
                                        <span v-if="task.ownerUsername" class="rmo-owner">@{{ task.ownerUsername }}</span>
                                    </div>
                                    <p class="rmo-snippet">{{ snippetOf(task) }}</p>
                                    <p class="rmo-meta">
                                        {{ repeatText(task.taskData?.repeat, task.taskData?.repeat_data) }}
                                        <template v-if="task.executedAt"> · 最近执行 {{ task.executedAt }}</template>
                                        <template v-if="task.taskData?.to"> · {{ task.taskData.to }}</template>
                                    </p>
                                    <div class="rmo-actions">
                                        <button v-if="canExecute(task)" type="button" class="rmo-chip ghost"
                                                :disabled="busyId === task.id" @click="executeTask(task)">立即发送</button>
                                        <button v-if="task.status === 2" type="button" class="rmo-chip ghost"
                                                :disabled="busyId === task.id" @click="enableTask(task)">启用</button>
                                        <button v-else-if="task.isDeleted !== 1" type="button" class="rmo-chip ghost"
                                                :disabled="busyId === task.id" @click="cancelTask(task)">禁用</button>
                                        <button type="button" class="rmo-chip ghost" @click="openEdit(task)">改规则</button>
                                        <button type="button" class="rmo-chip ghost" @click="openDetail(task.id)">详情</button>
                                        <button v-if="task.isDeleted === 1" type="button" class="rmo-chip ghost"
                                                :disabled="busyId === task.id" @click="restoreTask(task)">恢复</button>
                                        <button v-else type="button" class="rmo-chip ghost danger"
                                                :disabled="busyId === task.id" @click="removeTask(task)">删除</button>
                                    </div>
                                </div>
                            </li>
                        </ol>
                    </div>

                    <div class="rmo-pager">
                        <button type="button" class="rmo-chip ghost" :disabled="page <= 1" @click="goPage(-1)">
                            上一页
                        </button>
                        <span class="rmo-pager-text">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
                        <button type="button" class="rmo-chip ghost" :disabled="page >= totalPages"
                                @click="goPage(1)">下一页</button>
                    </div>
                </template>
            </section>
        </div>

        <!-- ===== 编辑器 ===== -->
        <div v-if="editor.open" class="rmo-mask" @click.self="editor.open = false">
            <form class="rmo-modal" @submit.prevent="submitEditor">
                <div class="rmo-modal-head">
                    <strong>{{ editor.mode === 'import' ? '从作业建提醒' : '自己写一封' }}</strong>
                    <button type="button" class="rmo-x" @click="editor.open = false">×</button>
                </div>

                <div class="rmo-modal-cols">
                    <!-- 左：选作业 -->
                    <div v-if="editor.mode === 'import'" class="rmo-modal-col">
                        <span class="rmo-col-label">选择作业</span>
                        <input v-model="jobQuery" type="search" class="rmo-input" aria-label="搜索待提交作业"
                               placeholder="搜索" />
                        <div v-if="!jobs.length" class="rmo-empty"><span>没有待提交的作业。</span></div>
                        <ul v-else class="rmo-jobs rmo-jobs-tall">
                            <li v-for="job in jobs" :key="job.key" class="rmo-job"
                                :class="{ picked: editor.job?.key === job.key }">
                                <button type="button" class="rmo-job-pick" @click="pickJob(job)">
                                    <span class="rmo-plat" :class="job.platform">{{ job.platformText }}</span>
                                    <strong>{{ job.title }}</strong>
                                    <span class="rmo-job-due">
                                        {{ job.dueDay ? `截止 ${job.dueDay} ${job.dueClock}` : '无截止时间' }}
                                    </span>
                                </button>
                            </li>
                        </ul>
                    </div>

                    <!-- 右：内容 + 时间 -->
                    <div class="rmo-modal-col">
                        <!-- 「提前 N 天」是在算一个具体日期，只在从作业导入 + 仅一次时才有意义：
                             自定义提醒没有截止时间可推，重复提醒的日期由规则决定 -->
                        <template v-if="editor.mode === 'import' && !editorRepeats">
                            <span class="rmo-col-label">提前多久提醒</span>
                            <div class="rmo-leads">
                                <button v-for="days in LEAD_DAYS" :key="days" type="button" class="rmo-chip"
                                        :class="{ on: editor.useLead && editor.lead === days && editor.job?.endTime }"
                                        :disabled="!editor.job?.endTime"
                                        @click="applyLead(days)">
                                    提前 {{ days }} 天
                                </button>
                            </div>
                            <p class="rmo-hint">
                                <template v-if="leadPreview">{{ leadPreview }}</template>
                                <template v-else-if="editor.job && !editor.job.endTime">
                                    这条作业没有截止时间，直接选下面的发送时间。
                                </template>
                                <template v-else>选一条作业后可以用它算发送时间，也可以直接指定。</template>
                            </p>
                        </template>

                        <span class="rmo-col-label">主题</span>
                        <input v-model="editor.subject" class="rmo-input" maxlength="200"
                               aria-label="邮件主题" placeholder="例如：⏰ 记得交高数作业" />

                        <span class="rmo-col-label">
                            正文
                            <span class="rmo-radio">
                                <label><input type="radio" value="text" :checked="editor.format === 'text'"
                                              @change="switchFormat('text')" /> 纯文本</label>
                                <label><input type="radio" value="html" :checked="editor.format === 'html'"
                                              @change="switchFormat('html')" /> HTML</label>
                            </span>
                        </span>
                        <textarea v-model="editor.content" class="rmo-input rmo-textarea" rows="7"
                                  aria-label="邮件正文"
                                  :placeholder="editor.format === 'html'
                                      ? '直接写 HTML，例如：<div>今晚 8 点前把实验报告写完</div>'
                                      : '写提醒时你想看到的话，例如：今晚 8 点前把实验报告写完，别拖。'"></textarea>

                        <!-- HTML 正文实时预览：sandbox 里渲染，脚本不会执行 -->
                        <div v-if="editor.format === 'html'" class="rmo-preview-box">
                            <div class="rmo-preview-head">
                                <span class="rmo-preview-title">预览</span>
                                <button type="button" class="rmo-chip ghost"
                                        @click="showEditorPreview = !showEditorPreview">
                                    {{ showEditorPreview ? '收起预览' : '展开预览' }}
                                </button>
                            </div>
                            <iframe v-if="showEditorPreview" class="rmo-frame" sandbox
                                    title="邮件 HTML 预览" :srcdoc="editorPreviewDoc"></iframe>
                            <p v-else class="rmo-hint">展开后在这里看这段 HTML 的实际效果。</p>
                        </div>

                        <!-- 重复规则的发送日由规则决定：这时只挑「几点发」，不再要一个具体日期 -->
                        <span v-if="!isImmediate" class="rmo-col-label">
                            {{ editorNeedsDate ? '发送时间' : '发送时刻' }}
                        </span>
                        <div v-if="!isImmediate" class="rmo-datetime" :class="{ solo: !editorNeedsDate }">
                            <AppDateTime v-if="editorNeedsDate" v-model="editor.date" mode="date"
                                         aria-label="发送日期"
                                         @update:model-value="editor.useLead = false" />
                            <AppDateTime v-if="!isImmediate" v-model="editor.time" mode="time"
                                         aria-label="发送时间"
                                         @update:model-value="editor.useLead = false" />
                        </div>

                        <span class="rmo-col-label">重复</span>
                        <!-- 这一项在弹窗靠底的位置，面板向上弹，避免被弹窗的滚动区裁掉 -->
                        <AppSelect v-model="editor.repeat" drop-up :options="REPEAT_OPTIONS"
                                   aria-label="重复规则" />

                        <div v-if="editor.repeat === 'weekly'" class="rmo-weeks">
                            <button v-for="day in WEEKDAYS" :key="day.value" type="button" class="rmo-week"
                                    :class="{ on: editor.weeklyDays.includes(day.value) }"
                                    @click="toggleWeekday(day.value)">{{ day.label }}</button>
                        </div>

                        <template v-if="editor.repeat === 'monthly'">
                            <span class="rmo-col-label">每月几号</span>
                            <input v-model.number="editor.monthlyDay" class="rmo-input" type="number" min="1" max="31" />
                        </template>

                        <p v-if="editor.repeat === 'immediate'" class="rmo-hint">创建后由调度器在 30 秒内发出。</p>
                        <p v-else-if="editorRepeats" class="rmo-hint">
                            按规则推算，下次发送：{{ editorNextSend }}
                        </p>
                    </div>
                </div>

                <div class="rmo-modal-foot">
                    <span class="rmo-foot-note">收件人 {{ email || '（账号未设置邮箱）' }}</span>
                    <button type="button" class="rmo-chip ghost" @click="editor.open = false">取消</button>
                    <button class="rmo-chip primary" type="submit" :disabled="saving || !email">
                        <span v-if="saving" class="spinner"></span>
                        <span>{{ saving ? '创建中…' : '创建提醒' }}</span>
                    </button>
                </div>
            </form>
        </div>

        <!-- ===== 建提醒前的确认 ===== -->
        <div v-if="confirmBox.open && confirmBox.job" class="rmo-mask" @click.self="confirmBox.open = false">
            <div class="rmo-modal rmo-modal-narrow">
                <div class="rmo-modal-head">
                    <strong>确认这条提醒</strong>
                    <button type="button" class="rmo-x" @click="confirmBox.open = false">×</button>
                </div>

                <div class="rmo-confirm">
                    <div class="rmo-confirm-job">
                        <span class="rmo-plat" :class="confirmBox.job.platform">{{ confirmBox.job.platformText }}</span>
                        <strong>{{ confirmBox.job.title }}</strong>
                    </div>
                    <p class="rmo-meta">
                        {{ confirmBox.job.dueDay
                            ? `截止 ${confirmBox.job.dueDay} ${confirmBox.job.dueClock}`
                            : '这条作业没有截止时间，按「N 天后」提醒' }}
                    </p>

                    <div class="rmo-lead-row">
                        <span class="rmo-lead-word">提前</span>
                        <AppSelect v-model="confirmBox.days" compact :options="DAY_OPTIONS"
                                   aria-label="提前几天提醒" @change="refreshConfirm()" />
                        <span class="rmo-lead-word">天</span>
                        <span class="rmo-lead-at">{{ confirmTimeText }} 发出</span>
                    </div>

                    <p v-if="confirmBox.clamped" class="rmo-warn">
                        这个时间已经过去了，实际会在 5 分钟后发出。
                    </p>
                </div>

                <div class="rmo-modal-foot">
                    <span class="rmo-foot-note">收件人 {{ email || '（账号未设置邮箱）' }}</span>
                    <button type="button" class="rmo-chip ghost" @click="confirmBox.open = false">取消</button>
                    <button type="button" class="rmo-chip primary" :disabled="creatingQuick || !email"
                            @click="confirmQuickCreate">
                        <span v-if="creatingQuick" class="spinner"></span>
                        <span>{{ creatingQuick ? '创建中…' : '确认创建' }}</span>
                    </button>
                </div>
            </div>
        </div>

        <!-- ===== 详情 ===== -->
        <div v-if="detail || detailLoading" class="rmo-mask" @click.self="detail = null">
            <div class="rmo-modal rmo-modal-narrow">
                <div class="rmo-modal-head">
                    <strong>提醒详情</strong>
                    <button type="button" class="rmo-x" @click="detail = null">×</button>
                </div>
                <div v-if="detailLoading && !detail" class="rmo-hint">加载中…</div>
                <template v-else-if="detail">
                    <h3 class="rmo-detail-subject">{{ subjectOf(detail.task) }}</h3>
                    <p class="rmo-meta">
                        {{ statusInfo(detail.task.status).text }} · 预定 {{ detail.task.scheduledTime }}
                        <template v-if="detail.task.executedAt"> · 最近执行 {{ detail.task.executedAt }}</template>
                        · {{ repeatText(detail.task.taskData?.repeat, detail.task.taskData?.repeat_data) }}
                    </p>
                    <iframe v-if="detail.task.taskData?.format === 'html'" class="rmo-frame rmo-frame-tall"
                            sandbox title="邮件 HTML 预览"
                            :srcdoc="previewDoc(detail.task.taskData?.content)"></iframe>
                    <pre v-else class="rmo-preview">{{ detail.task.taskData?.content }}</pre>

                    <span class="rmo-col-label">执行日志</span>
                    <p v-if="!detail.logs?.length" class="rmo-hint">还没有执行记录。</p>
                    <ul v-else class="rmo-logs">
                        <li v-for="log in detail.logs" :key="log.id" :class="{ bad: !log.success }">
                            <span class="rmo-log-time">{{ log.executedAt }}</span>
                            <span class="rmo-state" :class="log.success ? 'sent' : 'failed'">
                                {{ log.success ? '成功' : '失败' }}
                            </span>
                            <span class="rmo-log-trigger">{{ log.triggerType === 'auto' ? '自动' : '手动' }}</span>
                            <span>{{ log.message }}</span>
                        </li>
                    </ul>
                </template>
            </div>
        </div>

        <!-- ===== 改规则 ===== -->
        <div v-if="edit.open" class="rmo-mask" @click.self="edit.open = false">
            <form class="rmo-modal rmo-modal-narrow" @submit.prevent="submitEdit">
                <div class="rmo-modal-head">
                    <strong>改发送规则</strong>
                    <button type="button" class="rmo-x" @click="edit.open = false">×</button>
                </div>
                <p class="rmo-hint">只改时间与重复规则，主题和正文不变。</p>

                <!-- 重复规则的发送日由规则决定：这时只挑「几点发」，不再要一个具体日期 -->
                <span v-if="edit.repeat !== 'immediate'" class="rmo-col-label">
                    {{ editNeedsDate ? '发送时间' : '发送时刻' }}
                </span>
                <div v-if="edit.repeat !== 'immediate'" class="rmo-datetime" :class="{ solo: !editNeedsDate }">
                    <AppDateTime v-if="editNeedsDate" v-model="edit.date" mode="date" aria-label="发送日期" />
                    <AppDateTime v-if="edit.repeat !== 'immediate'" v-model="edit.time" mode="time"
                                 aria-label="发送时间" />
                </div>

                <span class="rmo-col-label">重复</span>
                <AppSelect v-model="edit.repeat" drop-up :options="REPEAT_OPTIONS" aria-label="重复规则" />

                <div v-if="edit.repeat === 'weekly'" class="rmo-weeks">
                    <button v-for="day in WEEKDAYS" :key="day.value" type="button" class="rmo-week"
                            :class="{ on: edit.weeklyDays.includes(day.value) }"
                            @click="toggleEditWeekday(day.value)">{{ day.label }}</button>
                </div>

                <template v-if="edit.repeat === 'monthly'">
                    <span class="rmo-col-label">每月几号</span>
                    <input v-model.number="edit.monthlyDay" class="rmo-input" type="number" min="1" max="31" />
                </template>

                <p v-if="edit.repeat === 'immediate'" class="rmo-hint">保存后由调度器在 30 秒内发出。</p>
                <p v-else-if="editRepeats" class="rmo-hint">按规则推算，下次发送：{{ editNextSend }}</p>

                <div class="rmo-modal-foot">
                    <span class="rmo-foot-note"></span>
                    <button type="button" class="rmo-chip ghost" @click="edit.open = false">取消</button>
                    <button class="rmo-chip primary" type="submit" :disabled="savingEdit">
                        <span v-if="savingEdit" class="spinner"></span>
                        <span>{{ savingEdit ? '保存中…' : '保存' }}</span>
                    </button>
                </div>
            </form>
        </div>
    </div>
</template>
