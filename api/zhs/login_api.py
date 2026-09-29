"""
智慧树自动登录 + 课程列表 + 作业考试提醒 FastAPI 服务

接口：
    GET  /                       服务健康检查
    POST /api/login/password     账号密码登录（自动识别滑块验证码）
    POST /api/login/cookie       Cookie 登录（JSON 请求，不读写 cookies.json）
    POST /api/course/list        通过 Cookie 获取学生课程列表（返回 JSON）
    POST /api/homework/reminder  通过 Cookie 获取作业/考试提醒列表（返回带 link 的 JSON）

启动：
    uvicorn login_api:app --host localhost --port 2001 --reload
    或
    python login_api.py
"""

import base64
import io
import json
import time
from typing import Any, Optional
from urllib.parse import quote, unquote

import requests
import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image as PILImage
from pydantic import BaseModel, Field, ConfigDict

from captcha_recognizer.slider import Slider
from Zhihuishu import Zhihuishu
from captcha.captcha import Captcha

# ================== 配置 ==================
CAPTCHA_ID = "75f9f716460a422f89a628f50fd8cc2b"
CAPTCHA_REFERER = "https://passport.zhihuishu.com/login"

MAX_CAPTCHA_RETRY = 5  # 验证码识别失败最大重试次数
RETRY_INTERVAL = 1.0  # 每次重试间隔（秒）

# 课程列表接口
COURSE_LIST_URL = "https://hike-teaching.zhihuishu.com/educational/platform/getStudentCourseList"
COURSE_LIST_HEADERS = {
    'Accept': '*/*',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'DNT': '1',
    'Origin': 'https://hike-course-homepage.zhihuishu.com',
    'Pragma': 'no-cache',
    'Referer': 'https://hike-course-homepage.zhihuishu.com/',
    'Sec-Fetch-Dest': 'empty',
    'Sec-Fetch-Mode': 'cors',
    'Sec-Fetch-Site': 'same-site',
    'User-Agent': (
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
        '(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36 Edg/154.0.0.0'
    ),
    'sec-ch-ua': '"Chromium";v="154", "Microsoft Edge";v="154", "Not A(Brand";v="99"',
    'sec-ch-ua-mobile': '?0',
    'sec-ch-ua-platform': '"Windows"',
}

# 作业 / 考试提醒列表接口
HOMEWORK_REMINDER_URL = "https://hike-teaching.zhihuishu.com/homeworkExamReminder/findReminderList"

# 课程「作业 / 考试」直达地址模板，后面拼 courseId
WORK_URL_TEMPLATE = "https://wenda.zhihuishu.com/stu/courseInfo/homeworkExam/?courseId="

# ================== FastAPI ==================
app = FastAPI(
    title="智慧树自动登录 API",
    description="提供账号密码登录、Cookie 登录、课程列表与作业/考试提醒获取",
    version="1.2.0",
)

# 配置 CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ================== 请求 / 响应模型 ==================
class PasswordLoginRequest(BaseModel):
    username: str = Field(..., description="智慧树账号（手机号）")
    password: str = Field(..., description="智慧树密码")


class CookieLoginRequest(BaseModel):
    # populate_by_name=True：既支持 alias("validate")，也支持字段名 validate_token
    model_config = ConfigDict(populate_by_name=True)

    username: str = Field(..., description="智慧树账号")
    password: str = Field("", description="智慧树密码（可为空）")
    validate_token: str = Field("", alias="validate", description="登录 validate 凭证")
    fp: str = Field("", description="浏览器指纹 fp")
    cookies: Any = Field(None, description="Cookie 数据，dict 或 list 均可")


class LoginResponse(BaseModel):
    # populate_by_name=True：构造时 validate=... 或 validate_token=... 都能用
    model_config = ConfigDict(populate_by_name=True)

    success: bool
    message: str
    username: Optional[str] = None
    # 新的用户信息载体：{realName, headPicUrl, uuid, username}
    user_info: Optional[Any] = None
    # 兼容旧字段：仍会填上 realName
    real_name: Optional[Any] = None
    validate_token: Optional[str] = Field(None, alias="validate")
    fp: Optional[str] = None
    cookies: Optional[Any] = None


class CourseListRequest(BaseModel):
    """课程列表请求体"""

    model_config = ConfigDict(populate_by_name=True)

    # 必填：登录后的 Cookie（dict / list / 原始 cookie 字符串均可）
    cookies: Any = Field(..., description="Cookie 数据，dict、list 或 'a=1; b=2' 字符串")
    # 可选：不传则自动从 CASLOGC 中解析
    uuid: Optional[str] = Field(None, description="用户 uuid，不传则自动从 CASLOGC 解析")
    year: str = Field("", description="学年，如 2024（可为空）")
    term: str = Field("", description="学期（可为空）")


class CourseListResponse(BaseModel):
    success: bool
    message: str
    uuid: Optional[str] = None
    # 智慧树接口返回的原始 JSON
    data: Optional[Any] = None


class HomeworkReminderRequest(BaseModel):
    """作业 / 考试提醒列表请求体"""

    model_config = ConfigDict(populate_by_name=True)

    # 必填：登录后的 Cookie（dict / list / 原始 cookie 字符串均可）
    cookies: Any = Field(..., description="Cookie 数据，dict、list 或 'a=1; b=2' 字符串")
    # 可选：不传则自动从 CASLOGC 中解析
    uuid: Optional[str] = Field(None, description="用户 uuid，不传则自动从 CASLOGC 解析")

    # 与原脚本保持一致的分页 / 筛选参数
    choice_tab: str = Field("0", alias="choiceTab", description="筛选 tab，默认 0")
    page_num: str = Field("0", alias="pageNum", description="页码，默认 0")
    page_size1: str = Field("1000", alias="pageSize1", description="分页大小1，默认 1000")
    page_size2: str = Field("1000", alias="pageSize2", description="分页大小2，默认 1000")


class HomeworkReminderResponse(BaseModel):
    success: bool
    message: str
    uuid: Optional[str] = None
    # 已为每个 HomeworkExamReminder 项补上 link 字段的原始 JSON
    data: Optional[Any] = None


# ================== 工具函数 ==================
def profile_of(user_info: Any) -> Optional[dict]:
    """校验并取出智慧树用户信息。

    Zhihuishu.get_info() 成功时返回
    {"realName", "headPicUrl", "uuid", "username"}，失败时返回 -1。
    这里统一成一个「有 realName 的 dict 或 None」的约定。
    """
    if not isinstance(user_info, dict):
        return None
    real_name = user_info.get("realName")
    if not real_name or real_name == -1:
        return None
    return user_info


def real_name_of(user_info: Optional[dict]) -> Optional[str]:
    """从用户信息里取姓名，供旧的 real_name 字段兼容使用。"""
    return user_info.get("realName") if user_info else None


def _cookie_item_to_dict(item: Any) -> Any:
    """把单个 Cookie 对象转成 dict"""
    if isinstance(item, dict):
        return item
    data: dict = {}
    for attr in ("name", "value", "domain", "path", "secure", "expires"):
        v = getattr(item, attr, None)
        if v is not None:
            data[attr] = v
    return data or str(item)


def cookies_to_jsonable(cookies: Any) -> Any:
    """把各种形态的 cookies 转成可 JSON 序列化的结构"""
    if cookies is None:
        return None
    if isinstance(cookies, dict):
        return cookies

    get_dict = getattr(cookies, "get_dict", None)
    if callable(get_dict):
        try:
            return get_dict()
        except Exception:
            pass

    if isinstance(cookies, (list, tuple, set)):
        return [_cookie_item_to_dict(c) for c in cookies]

    if hasattr(cookies, "__iter__") and not isinstance(cookies, (str, bytes)):
        return [_cookie_item_to_dict(c) for c in cookies]

    return str(cookies)


def extract_cookies(obj: Any) -> Any:
    """从 Zhihuishu 实例上取出 cookies"""
    if hasattr(obj, "cookies"):
        return getattr(obj, "cookies")
    session = getattr(obj, "session", None)
    if session is not None and hasattr(session, "cookies"):
        return session.cookies
    return None


def normalize_cookies(cookies: Any) -> dict:
    """把各种形态的 cookies 统一成 {name: value} 字典。

    支持：
      - {"acw_tc": "xxx", "JSESSIONID": "yyy"}
      - [{"name": "acw_tc", "value": "xxx"}, ...]
      - "acw_tc=xxx; JSESSIONID=yyy"
      - requests.cookies.RequestsCookieJar
    """
    if cookies is None:
        return {}

    # 原始 cookie 字符串
    if isinstance(cookies, str):
        out: dict = {}
        for part in cookies.split(";"):
            if "=" in part:
                k, v = part.split("=", 1)
                out[k.strip()] = v.strip()
        return out

    # dict
    if isinstance(cookies, dict):
        # 单条 cookie 结构 {"name": ..., "value": ...}
        if "name" in cookies and "value" in cookies and len(cookies) <= 6:
            return {str(cookies["name"]): str(cookies["value"])}
        return {str(k): str(v) for k, v in cookies.items()}

    # requests 的 CookieJar
    get_dict = getattr(cookies, "get_dict", None)
    if callable(get_dict):
        try:
            data = get_dict()
            if isinstance(data, dict):
                return {str(k): str(v) for k, v in data.items()}
        except Exception:
            pass

    # list / tuple / set / 可迭代对象
    if hasattr(cookies, "__iter__"):
        out = {}
        for item in cookies:
            if isinstance(item, dict):
                name = item.get("name")
                if name:
                    out[str(name)] = str(item.get("value", ""))
            else:
                name = getattr(item, "name", None)
                if name:
                    out[str(name)] = str(getattr(item, "value", ""))
        if out:
            return out

    return {}


def uuid_from_cookies(cookie_dict: dict) -> Optional[str]:
    """从 CASLOGC 里解析 uuid"""
    raw = cookie_dict.get("CASLOGC")
    if not raw:
        return None
    try:
        return json.loads(unquote(raw)).get("uuid")
    except Exception:
        return None


# ================== 验证码识别 ==================
def recognize_gap(bg_bytes: bytes):
    """用 captcha_recognizer 识别缺口 x 坐标（原始背景图像素）
    返回 (gap_x, confidence)
    """
    box, confidence = Slider().identify(source=bg_bytes, show=False)
    if isinstance(box, dict):
        gap_x = int(box.get("x", 0))
    else:
        gap_x = int(box[0])
    return gap_x, float(confidence)


# ================== 账号密码登录（含滑块验证码） ==================
def captcha_login(username: str, password: str):
    """验证码自动识别并登录

    返回 (zhihuishu, validate, fp, error)
    """
    last_error = "验证码识别失败"

    for attempt in range(1, MAX_CAPTCHA_RETRY + 1):
        print(f"--- 验证码尝试 {attempt}/{MAX_CAPTCHA_RETRY} ---")

        # 构造验证码对象本身就会请求网易盾，网络异常或响应结构变化都要算「本次失败」，
        # 不能让它冒到接口层变成 500
        try:
            capt_cha = Captcha(CAPTCHA_ID, CAPTCHA_REFERER)
            captcha_data = capt_cha.captcha_data
        except Exception as e:
            last_error = f"验证码初始化失败：{e}"
            print(last_error)
            time.sleep(RETRY_INTERVAL)
            continue

        # 1. 解码背景图
        try:
            bg_bytes = base64.b64decode(captcha_data["bigImage"])
        except Exception as e:
            last_error = f"背景图解码失败：{e}"
            print(last_error)
            continue

        try:
            bg_img = PILImage.open(io.BytesIO(bg_bytes))
            bg_width, _ = bg_img.size
        except Exception as e:
            last_error = f"背景图读取失败：{e}"
            print(last_error)
            time.sleep(RETRY_INTERVAL)
            continue

        print(f"[识别] 背景图尺寸 = {bg_img.size}")

        # 2. 识别缺口位置
        try:
            gap_x, confidence = recognize_gap(bg_bytes)
        except Exception as e:
            last_error = f"缺口识别失败：{e}"
            print("[识别] 失败：", e)
            time.sleep(RETRY_INTERVAL)
            continue

        print(f"[识别] 缺口 x={gap_x}, 置信度={confidence:.4f}")

        if gap_x <= 0:
            last_error = "缺口坐标为 0"
            print("缺口坐标为 0，跳过本次")
            time.sleep(RETRY_INTERVAL)
            continue

        # 3. 换算成比例
        move_rate = gap_x / bg_width * 100
        move_rate = min(max(move_rate, 0), 100)
        print(f"[提交] 比例 = {move_rate:.2f}")

        # 4. 提交验证
        try:
            check_res = json.loads(capt_cha.check_captcha(move_rate))
            result = check_res["data"]["result"]
        except Exception as e:
            last_error = f"提交验证码失败：{e}"
            print(last_error)
            time.sleep(RETRY_INTERVAL)
            continue

        if not result:
            last_error = "验证码校验未通过"
            print("验证失败：", check_res)
            time.sleep(RETRY_INTERVAL)
            continue

        print("\033[1;32m验证码通过\033[0m")

        validate = check_res.get("data", {}).get("validate")
        if not validate:
            last_error = "验证码已通过但未返回 validate"
            print(last_error)
            time.sleep(RETRY_INTERVAL)
            continue
        fp = capt_cha.fp

        # 5. 登录（加密要走 JS 引擎，登录要发请求，任一环节异常都只是本次失败）
        try:
            zhihuishu = Zhihuishu(username, password, validate, fp)
            secret_str = capt_cha.encrypt_login_data(username, password, validate, fp)
            status_tuple = zhihuishu.login(secret_str)
        except Exception as e:
            last_error = f"登录请求异常：{e}"
            print(last_error)
            time.sleep(RETRY_INTERVAL)
            continue

        if status_tuple[0] == 0:
            return zhihuishu, validate, fp, None

        error_msgs = {
            -2: "用户名或密码错误",
            -1: "出现未知错误",
            -6: "你已连续5次登录失败，请5分钟后尝试",
        }
        msg = error_msgs.get(status_tuple[0], f"未知错误({status_tuple[0]})")
        return None, validate, fp, msg

    return None, None, None, f"验证码连续 {MAX_CAPTCHA_RETRY} 次识别失败：{last_error}"


# ================== 课程对象定位 ==================
# 课程对象数组可能出现的层级（不同网关返回的结构不一致）：
#   {..., "data": {"courses": [...]}}、{..., "courses": [...]}
#   {..., "rt": {"studyCourses": [...], "archiveCourses": [...], "courses": [...]}}
COURSE_LIST_PATHS: tuple[tuple[str, ...], ...] = (
    ("data", "courses"),
    ("courses",),
    ("rt", "studyCourses"),
    ("rt", "archiveCourses"),
    ("rt", "courses"),
)


def iter_course_objects(payload: Any):
    """安全遍历响应里所有的课程对象。

    按 COURSE_LIST_PATHS 逐个路径尝试取值：
    任一层不是 dict、或最终不是 list，就跳过该路径；
    payload 本身不是 dict 时也不会报错。
    """
    seen: set[int] = set()
    for path in COURSE_LIST_PATHS:
        node: Any = payload
        for key in path:
            if not isinstance(node, dict):
                node = None
                break
            node = node.get(key)
        if not isinstance(node, list):
            continue
        for course in node:
            # 同一个对象可能被多条路径命中，用 id 去重避免重复写入
            if isinstance(course, dict) and id(course) not in seen:
                seen.add(id(course))
                yield course


def attach_work_url(payload: Any) -> int:
    """给每门课补上「作业 / 考试」直达链接，返回写入成功的课程数。

    courseId 缺失或为空时跳过，不写入 workurl，避免生成无效链接。
    """
    count = 0
    for course in iter_course_objects(payload):
        course_id = course.get("courseId")
        if course_id:
            course["workurl"] = WORK_URL_TEMPLATE + str(course_id)
            count += 1
    return count


# ================== 作业 / 考试提醒：链接生成 ==================
def generate_link(item: dict) -> str:
    """根据 importantInfo.js 中的 goExam / doDelayHomework 逻辑生成链接。

    默认所有项都尝试生成链接（忽略 isDelay 等限制）。
    """
    course_id = item.get('courseId')
    item_id = item.get('id')
    status = item.get('status')
    item_type = item.get('type')
    exam_type = item.get('examType')
    # 上游有时把 paperId 返回成字符串或空串，下面要跟数字比较，这里统一成 int
    try:
        paper_id = int(item.get('paperId') or 0)
    except (TypeError, ValueError):
        paper_id = 0
    is_end_submit = item.get('isEndSubmit')
    group_id = item.get('groupId')
    project_id = item.get('projectId')

    link = ''

    # 小组作业
    if item_type == 3:
        if group_id is not None and project_id is not None:
            back_url = quote('https://www.zhihuishu.com')  # 占位，实际为 window.location
            link = (
                f'https://hikestuh5.zhihuishu.com/groupTeaching.html'
                f'#/stu/homeworkQ/homeworkDetail/{item_id}/doHomeWork/{status}/1/'
                f'{course_id}/{group_id}/{project_id}?backUrl={back_url}'
            )
        else:
            link = ''

    # 考试
    elif item_type == 2:
        if exam_type == 1:
            # 线上考试：无论状态如何都生成链接（原逻辑会判断是否开始，这里忽略）
            link = (
                f'https://hiexam.zhihuishu.com/atHomeworkExam/stu/studentExamQ/examVerification/'
                f'{course_id}/{item_id}/{status}/{paper_id}'
            )
        elif exam_type in (2, 3):
            # 线下考试，无链接
            link = ''

    # 作业
    elif item_type == 1:
        if exam_type == 0:
            if status == 2:
                if paper_id and paper_id > 0:
                    link = (
                        f'https://hiexam.zhihuishu.com/atHomeworkExam/stu/homeworkQ/exerciseList/'
                        f'{item_id}/exerciseList/7/6/{course_id}'
                    )
                else:
                    link = (
                        f'https://hiexam.zhihuishu.com/atHomeworkExam/stu/homeworkQ/homeworkDetail/'
                        f'{item_id}/doHomeWork/1/2/{course_id}'
                    )
            else:
                if paper_id and paper_id > 0:
                    e = None
                    if is_end_submit == 1:
                        e = 7
                    else:
                        if status == 1:
                            e = 6
                        elif status in (2, 3, 4):
                            e = 7
                        elif status == 5:
                            e = 8

                    if e is not None:
                        link = (
                            f'https://hiexam.zhihuishu.com/atHomeworkExam/stu/homeworkQ/homeworkDetail/'
                            f'{item_id}/homeworkDetail/6/{e}/{course_id}'
                        )
                else:
                    link = (
                        f'https://hiexam.zhihuishu.com/atHomeworkExam/stu/homeworkQ/homeworkDetail/'
                        f'{item_id}/homeworkDetail/{status}/1/{course_id}'
                    )

    return link


def attach_reminder_links(payload: Any) -> int:
    """给 data['rt']['Reminder'][*]['HomeworkExamReminder'][*] 补上 link 字段。

    返回写入成功的条目数；层级缺失或类型不符时静默跳过，不影响主流程。
    """
    if not isinstance(payload, dict):
        return 0

    rt = payload.get("rt")
    if not isinstance(rt, dict):
        return 0

    reminders = rt.get("Reminder")
    if not isinstance(reminders, list):
        return 0

    count = 0
    for reminder in reminders:
        if not isinstance(reminder, dict):
            continue
        items = reminder.get("HomeworkExamReminder")
        if not isinstance(items, list):
            continue
        for item in items:
            if isinstance(item, dict):
                item["link"] = generate_link(item)
                count += 1
    return count


# ================== 接口 ==================
@app.get("/", summary="服务健康检查")
def root():
    return {"service": "zhihuishu-login", "status": "ok"}


@app.post(
    "/api/login/password",
    response_model=LoginResponse,
    response_model_by_alias=True,
    summary="账号密码登录",
    description="自动识别滑块验证码并登录，成功后返回 validate / fp / cookies",
)
def api_login_password(req: PasswordLoginRequest):
    zhihuishu, validate, fp, err = captcha_login(req.username, req.password)

    if zhihuishu is None:
        return LoginResponse(
            success=False,
            message=err or "登录失败",
            username=req.username,
            validate_token=validate,
            fp=fp,
        )

    try:
        user_info = profile_of(zhihuishu.get_info())
    except Exception as e:
        return LoginResponse(
            success=False,
            message=f"获取用户信息失败：{e}",
            username=req.username,
            validate_token=validate,
            fp=fp,
        )

    if user_info is None:
        return LoginResponse(
            success=False,
            message="登录状态异常，请重试",
            username=req.username,
            validate_token=validate,
            fp=fp,
        )

    print({
        'success': True,
        'message': "登录成功",
        'username': req.username,
        'user_info': user_info,
        'real_name': real_name_of(user_info),
        'validate_token': validate,
        'fp': fp,
        'cookies': cookies_to_jsonable(extract_cookies(zhihuishu)),
    })
    return LoginResponse(
        success=True,
        message="登录成功",
        username=req.username,
        user_info=user_info,
        real_name=real_name_of(user_info),
        validate_token=validate,
        fp=fp,
        cookies=cookies_to_jsonable(extract_cookies(zhihuishu)),
    )


@app.post(
    "/api/login/cookie",
    response_model=LoginResponse,
    response_model_by_alias=True,
    summary="Cookie 登录",
    description="使用已有的 validate / fp / cookies 直接登录",
)
def api_login_cookie(req: CookieLoginRequest):
    if not req.cookies:
        return LoginResponse(
            success=False,
            message="缺少 cookies 参数",
            username=req.username,
            validate_token=req.validate_token,
            fp=req.fp,
        )

    try:
        zhihuishu = Zhihuishu(
            req.username,
            req.password,
            req.validate_token,
            req.fp,
            req.cookies,
        )
    except Exception as e:
        return LoginResponse(
            success=False,
            message=f"初始化会话失败：{e}",
            username=req.username,
            validate_token=req.validate_token,
            fp=req.fp,
        )

    try:
        user_info = profile_of(zhihuishu.get_info())
    except Exception as e:
        return LoginResponse(
            success=False,
            message=f"校验 Cookies 失败：{e}",
            username=req.username,
            validate_token=req.validate_token,
            fp=req.fp,
        )

    if user_info is None:
        return LoginResponse(
            success=False,
            message="Cookies 已失效，需要重新登录",
            username=req.username,
            validate_token=req.validate_token,
            fp=req.fp,
        )

    latest_cookies = extract_cookies(zhihuishu)
    return LoginResponse(
        success=True,
        message="登录成功",
        username=req.username,
        user_info=user_info,
        real_name=real_name_of(user_info),
        validate_token=req.validate_token,
        fp=req.fp,
        cookies=cookies_to_jsonable(latest_cookies) if latest_cookies else req.cookies,
    )


@app.post(
    "/api/course/list",
    response_model=CourseListResponse,
    summary="获取学生课程列表",
    description="传入登录后的 Cookie，返回智慧树 getStudentCourseList 接口的原始 JSON 数据",
)
def api_course_list(req: CourseListRequest):
    # 1. 统一 cookie 形态
    cookie_dict = normalize_cookies(req.cookies)
    if not cookie_dict:
        return CourseListResponse(
            success=False,
            message="cookies 无效或为空",
        )

    # 2. 解析 uuid
    user_uuid = req.uuid or uuid_from_cookies(cookie_dict)
    if not user_uuid:
        return CourseListResponse(
            success=False,
            message="无法确定 uuid，请手动传入 uuid 或提供含 CASLOGC 的 cookies",
        )

    # 3. 组装参数（dateFormate 保留原脚本的拼写，注意不要"纠正"）
    params = {
        'year': req.year,
        'term': req.term,
        'uuid': user_uuid,
        'dateFormate': str(time.time() * 1000),
    }

    # 4. 请求智慧树接口
    try:
        response = requests.get(
            COURSE_LIST_URL,
            params=params,
            cookies=cookie_dict,
            headers=COURSE_LIST_HEADERS,
            timeout=15,
        )
    except requests.RequestException as e:
        return CourseListResponse(
            success=False,
            message=f"请求课程列表失败：{e}",
            uuid=user_uuid,
        )

    # 5. 解析 JSON
    try:
        data = response.json()
    except ValueError:
        return CourseListResponse(
            success=False,
            message=f"返回内容不是合法 JSON（HTTP {response.status_code}）：{response.text[:200]}",
            uuid=user_uuid,
        )
    # HTTP 层失败（401/500 等）即使 body 是合法 JSON 也不能当成功，
    # 否则 Cookie 失效会被判成「查到了 0 门课」
    if response.status_code != 200:
        return CourseListResponse(
            success=False,
            message=f"接口返回 HTTP {response.status_code}（Cookie 可能已失效）：{response.text[:200]}",
            uuid=user_uuid,
        )
    # 补「作业 / 考试」直达链接：层级对不上时静默跳过，不影响主流程
    print(f"[课程列表] 已补作业链接 {attach_work_url(data)} 门课程")
    # 6. 智慧树业务层判定（code 有时是字符串，统一按字符串比）
    if isinstance(data, dict):
        code = data.get("code")
        if code is not None and str(code) not in ("0", "200"):
            return CourseListResponse(
                success=False,
                message=data.get("message") or f"接口返回异常 code={code}",
                uuid=user_uuid,
                data=data,
            )

    return CourseListResponse(
        success=True,
        message="获取成功",
        uuid=user_uuid,
        data=data,
    )


@app.post(
    "/api/homework/reminder",
    response_model=HomeworkReminderResponse,
    summary="获取作业 / 考试提醒列表",
    description=(
            "传入登录后的 Cookie，调用 findReminderList 接口，"
            "并为每条 HomeworkExamReminder 补上可直接访问的 link 字段，返回原始 JSON"
    ),
)
def api_homework_reminder(req: HomeworkReminderRequest):
    # 1. 统一 cookie 形态
    cookie_dict = normalize_cookies(req.cookies)
    if not cookie_dict:
        return HomeworkReminderResponse(
            success=False,
            message="cookies 无效或为空",
        )

    # 2. 解析 uuid
    user_uuid = req.uuid or uuid_from_cookies(cookie_dict)
    if not user_uuid:
        return HomeworkReminderResponse(
            success=False,
            message="无法确定 uuid，请手动传入 uuid 或提供含 CASLOGC 的 cookies",
        )

    # 3. 组装参数（dateFormate 保留原脚本的拼写，注意不要"纠正"）
    params = {
        'choiceTab': req.choice_tab,
        'pageNum': req.page_num,
        'pageSize1': req.page_size1,
        'pageSize2': req.page_size2,
        'uuid': user_uuid,
        'dateFormate': str(time.time() * 1000),
    }

    # 4. 请求智慧树接口
    try:
        response = requests.get(
            HOMEWORK_REMINDER_URL,
            params=params,
            cookies=cookie_dict,
            headers=COURSE_LIST_HEADERS,
            timeout=15,
        )
    except requests.RequestException as e:
        return HomeworkReminderResponse(
            success=False,
            message=f"请求作业提醒失败：{e}",
            uuid=user_uuid,
        )

    # 5. 解析 JSON
    try:
        data = response.json()
    except ValueError:
        return HomeworkReminderResponse(
            success=False,
            message=f"返回内容不是合法 JSON（HTTP {response.status_code}）：{response.text[:200]}",
            uuid=user_uuid,
        )

    # HTTP 层失败即使 body 是合法 JSON 也不能当成功（Cookie 失效常表现为 401/500）
    if response.status_code != 200:
        return HomeworkReminderResponse(
            success=False,
            message=f"接口返回 HTTP {response.status_code}（Cookie 可能已失效）：{response.text[:200]}",
            uuid=user_uuid,
        )

    # 6. 给每条提醒补 link：层级对不上时静默跳过，不影响主流程
    print(f"[作业提醒] 已生成链接 {attach_reminder_links(data)} 条")

    # 7. 智慧树业务层判定（code 有时是字符串，统一按字符串比）
    if isinstance(data, dict):
        code = data.get("code")
        if code is not None and str(code) not in ("0", "200"):
            return HomeworkReminderResponse(
                success=False,
                message=data.get("message") or f"接口返回异常 code={code}",
                uuid=user_uuid,
                data=data,
            )

    return HomeworkReminderResponse(
        success=True,
        message="获取成功",
        uuid=user_uuid,
        data=data,
    )


if __name__ == "__main__":
    uvicorn.run(app, host="localhost", port=2001)