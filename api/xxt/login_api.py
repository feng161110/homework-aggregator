# -*- coding: utf-8 -*-
"""
学习通（超星）登录 / Cookie 校验 / 电脑版链接生成 / 课程列表 —— FastAPI 版本

安装依赖：
    pip install fastapi uvicorn requests pycryptodome beautifulsoup4

启动：
    uvicorn login_api:app --host localhost --port 2003 --reload
"""

import base64
import re
from typing import Dict, Optional
from urllib.parse import urlparse, parse_qs

import requests
import uvicorn
from bs4 import BeautifulSoup
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

# ==================== 常量 ====================
KEY = "u2oh6Vu^HWe4_AES"
LOGIN_URL = "https://passport2.chaoxing.com/fanyalogin"
USER_INFO_URL = "https://i.chaoxing.com/base"
TIMEOUT = 20

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0")

# ---- 电脑版链接相关常量 ----
CX_MIDDLE_PAGE = "https://mooc1.chaoxing.com/visit/stucoursemiddle"
CX_WORK_LIST = "https://mooc1.chaoxing.com/mooc2/work/list"
CX_WORK_TASK = "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task"
CX_EXAM_LIST = "https://mooc1.chaoxing.com/exam-ans/mooc2/exam/exam-list"
CX_EXAM_LOOK = "https://mooc1-api.chaoxing.com/exam-ans/exam/test/look"
CX_STU_WORK = "https://mooc1-api.chaoxing.com/work/stu-work"
CX_EXAM_CODE = "https://mooc1-api.chaoxing.com/exam-ans/exam/phone/examcode"

# ---- 课程列表相关常量 ----
CX_COURSE_LIST = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata"

# pageHeader 映射表
PAGE_HEADER_MAP = {
    "0": "任务",
    "1": "章节",
    "2": "讨论",
    "3": "资料",
    "4": "错题集",
    "6": "学习记录",
    "8": "作业",
    "9": "考试",
}

# ==================== FastAPI 初始化 & CORS ====================
app = FastAPI(
    title="Chaoxing API",
    description="学习通登录 & Cookie 有效性校验 & 电脑版链接生成 & 课程列表接口",
    version="1.5.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ==================== 数据模型 ====================
class LoginRequest(BaseModel):
    username: str = Field(..., description="手机号 / 用户名")
    password: str = Field(..., description="密码")


class CookieRequest(BaseModel):
    cookies: Dict[str, str] = Field(..., description="浏览器或登录接口拿到的 cookie 字典")


class CourseListRequest(BaseModel):
    cookies: Dict[str, str] = Field(..., description="浏览器或登录接口拿到的 cookie 字典")
    courseType: str = Field("1", description="1=我学的课(学生)，0=我教的课(教师)")
    courseFolderId: str = Field("0", description="课程文件夹 ID，0 表示全部")
    query: str = Field("", description="搜索关键字")
    pageHeader: str = Field("-1", description="列表类型，-1 表示全部")
    single: str = Field("0", description="是否单页")
    superstarClass: str = Field("0", description="是否超星班级")
    isFirefly: str = Field("0", description="是否萤火虫")
    fid: str = Field("", description="fid")
    from_: str = Field("", alias="from", description="来源标识")

    class Config:
        populate_by_name = True


# ==================== 工具函数 ====================
def encrypt_by_aes(message: str, key: str) -> str:
    """AES-CBC(PKCS7) 加密后 Base64 编码"""
    key_bytes = key.encode("utf-8")
    iv_bytes = key.encode("utf-8")
    data_bytes = message.encode("utf-8")
    cipher = AES.new(key_bytes, AES.MODE_CBC, iv_bytes)
    padded = pad(data_bytes, AES.block_size)
    ciphertext = cipher.encrypt(padded)
    return base64.b64encode(ciphertext).decode("utf-8")


def do_login(username: str, password: str) -> dict:
    """执行登录，返回统一结构"""
    uname = encrypt_by_aes(username, KEY)
    pwd = encrypt_by_aes(password, KEY)

    headers = {
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "DNT": "1",
        "Origin": "https://passport2.chaoxing.com",
        "Pragma": "no-cache",
        "Referer": ("https://passport2.chaoxing.com/login?fid=&newversion=true"
                    "&refer=https%3A%2F%2Fi.chaoxing.com"),
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "User-Agent": UA,
        "X-Requested-With": "XMLHttpRequest",
        "sec-ch-ua": '"Microsoft Edge";v="153", "Not_A Brand";v="8", "Chromium";v="153"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
    }

    data = {
        "fid": "-1",
        "uname": uname,
        "password": pwd,
        "refer": "https%3A%2F%2Fi.chaoxing.com",
        "t": "true",
        "forbidotherlogin": "0",
        "validate": "",
        "doubleFactorLogin": "0",
        "independentId": "0",
        "independentNameId": "0",
    }

    try:
        response = requests.post(LOGIN_URL, headers=headers, data=data, timeout=TIMEOUT)
    except requests.RequestException as e:
        return {"status": False, "message": f"请求异常: {e}", "cookies": {}}

    try:
        result = response.json()
    except ValueError:
        return {
            "status": False,
            "message": f"响应不是 JSON（状态码 {response.status_code}）",
            "cookies": {},
        }

    if response.status_code == 200 and result.get("status"):
        cookies = response.cookies.get_dict()
        return {
            "status": True,
            "message": result.get("msg") or "登录成功",
            "cookies": cookies,
            "name": result.get("name", ""),
        }

    return {
        "status": False,
        "message": result.get("msg") or result.get("message") or "登录失败",
        "cookies": {},
    }


def get_user_info(cookies: Dict[str, str]) -> dict:
    """用 cookie 访问个人主页，判断 cookie 是否仍然有效"""
    headers = {
        "Accept": ("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                   "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"),
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "DNT": "1",
        "Pragma": "no-cache",
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-User": "?1",
        "Upgrade-Insecure-Requests": "1",
        "User-Agent": UA,
        "sec-ch-ua": '"Microsoft Edge";v="153", "Not_A Brand";v="8", "Chromium";v="153"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
    }

    try:
        response = requests.get(USER_INFO_URL, cookies=cookies, headers=headers, timeout=TIMEOUT)
    except requests.RequestException as e:
        return {"status": False, "expired": False, "message": f"请求异常: {e}"}

    soup = BeautifulSoup(response.text, "html.parser")
    user_block = soup.select_one(".user-block")

    if user_block:
        avatar_tag = user_block.select_one("img.head-img")
        name_tag = user_block.select_one("p.user-name")
        return {
            "status": True,
            "expired": False,
            "name": name_tag.get_text(strip=True) if name_tag else None,
            "avatar": avatar_tag.get("src") if avatar_tag else None,
        }

    return {
        "status": False,
        "expired": True,
        "message": "Cookie 已失效或无效",
    }


# ============================================================
# 电脑版链接生成模块
# ============================================================

def _status_class(status: str) -> str:
    """
    状态语义（供前端上色），与前端 ui/homework.js 的 deriveStatusClass 保持同一套规则：
      - 互评相关（待互评 / 互评）              -> "review"
      - 其余「待 xx」「未 xx」都是还要去做的事 -> "pending"
        （待提交 / 待完成 / 待批阅 / 待批改 / 未提交 / 未交 / 未开始 / 进行中 …）
      - 其余（已提交 / 已完成 / 已批阅 / 空）  -> "done"
    """
    s = status or ""
    if re.search(r"互评", s):
        return "review"
    if re.search(r"待|未|进行中", s):
        return "pending"
    return "done"


def _find_text(li, *selectors) -> str:
    """按顺序尝试多个 CSS 选择器，返回第一个非空文本；全部未命中返回空串。"""
    for sel in selectors:
        try:
            el = li.select_one(sel)
        except Exception:
            continue
        if el:
            txt = el.get_text(strip=True)
            if txt:
                return txt
    return ""


def _session(cookies: Dict[str, str]) -> requests.Session:
    s = requests.Session()
    s.headers.update({
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
    })
    s.cookies.update(cookies)
    return s


def _extract_param(html: str, param_id: str) -> Optional[str]:
    """从 HTML 里提取 id="xxx" 的 value（宽松匹配）"""
    m = re.search(rf'id="{param_id}"[^>]*value="([^"]+)"', html)
    return m.group(1) if m else None


def _extract_cpi_digits(html: str) -> Optional[str]:
    """只匹配纯数字 cpi"""
    m = re.search(r'id="cpi"[^>]*value="(\d+)"', html)
    return m.group(1) if m else None


def _get_middle_page(sess: requests.Session, course_id: str, clazz_id: str) -> Optional[str]:
    """访问课程中间页，用于拿 workEnc / examEnc / cpi"""
    url = (f"{CX_MIDDLE_PAGE}?courseid={course_id}&clazzid={clazz_id}"
           f"&ismooc2=1&v=2")
    try:
        resp = sess.get(
            url,
            timeout=30,
            allow_redirects=True,
            headers={"Referer": "https://mooc2-ans.chaoxing.com/visit/interaction"},
        )
        return resp.text
    except requests.RequestException:
        return None


def _build_work_desktop_map(sess: requests.Session, course_id: str, clazz_id: str) -> dict:
    """返回 {workId: 电脑版作业链接}"""
    mid_html = _get_middle_page(sess, course_id, clazz_id)
    if not mid_html:
        raise RuntimeError("无法进入中间页")
    work_enc = _extract_param(mid_html, "workEnc")
    if not work_enc:
        raise RuntimeError("中间页未找到 workEnc")

    list_url = (f"{CX_WORK_LIST}?courseId={course_id}"
                f"&classId={clazz_id}&enc={work_enc}")
    html = sess.get(list_url, timeout=30).text

    out = {}
    for m in re.finditer(r"work/task\?([^\"'\\]+)", html):
        qs = m.group(1)
        p = parse_qs(qs)
        work_id = (p.get("workId") or [None])[0]
        answer_id = (p.get("answerId") or [None])[0]
        if work_id and answer_id:
            out[work_id] = f"{CX_WORK_TASK}?{qs}"
    return out


def _probe_exam_desktop(
        sess: requests.Session,
        course_id: str,
        clazz_id: str,
        exam_id: str,
        raw_url: str,
) -> Optional[str]:
    """探测单场考试的电脑版链接。"""
    try:
        resp = sess.get(
            raw_url,
            timeout=30,
            allow_redirects=True,
            headers={"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"},
        )
    except requests.RequestException as e:
        print(f"    ✗ 考试 {exam_id} 请求失败: {e}")
        return None

    final_url = resp.url or ""
    html = resp.text or ""

    try:
        qs = parse_qs(urlparse(final_url).query)
        answer_id = (qs.get("examAnswerId") or [""])[0]
        cpi_in_url = (qs.get("cpi") or [""])[0]
    except Exception:
        answer_id, cpi_in_url = "", ""

    if answer_id.isdigit() and cpi_in_url.isdigit():
        return (
            f"{CX_EXAM_LOOK}?"
            f"courseId={course_id}&classId={clazz_id}"
            f"&examId={exam_id}&examAnswerId={answer_id}&cpi={cpi_in_url}"
        )

    page_cpi = _extract_cpi_digits(html)
    if page_cpi:
        return (
            f"{CX_EXAM_LIST}?"
            f"courseid={course_id}&clazzid={clazz_id}"
            f"&cpi={page_cpi}"
        )

    return None


def _parse_stu_work(html: str) -> list:
    """解析 stu-work 聚合页。"""
    soup = BeautifulSoup(html, "html.parser")
    items = []
    for li in soup.find_all("li"):
        data_url = li.get("data", "")
        if not data_url or not re.search(r"taskrefId|workRelationId", data_url):
            continue
        qs = parse_qs(data_url.split("?", 1)[1] if "?" in data_url else "")
        work_id = (qs.get("taskrefId") or qs.get("workRelationId") or [""])[0]
        if not work_id:
            continue

        title_p = li.find("p", {"aria-hidden": "true"})
        img = li.find("img")

        status = _find_text(
            li,
            ".status",
            "span.status",
            ".work-status",
            ".workStatus",
            "[class~='status']",
            "[class*='work-status']",
            "[class*='workStatus']",
        )
        remain_text = _find_text(
            li,
            ".fr",
            ".remain",
            ".remainTime",
            ".remain-time",
            "[class*='remain']",
        )

        expired = bool(img and "task-work-gray" in (img.get("src") or ""))

        items.append({
            "courseId": (qs.get("courseId") or [""])[0],
            "clazzId": (qs.get("clazzId") or [""])[0],
            "workId": work_id,
            "title": title_p.get_text(strip=True) if title_p else "",
            "rawUrl": data_url,
            "expired": expired,
            "status": status or None,
            "remainText": remain_text or None,
        })
    return items


def _parse_exam_code(html: str) -> list:
    """解析 examcode 聚合页。"""
    soup = BeautifulSoup(html, "html.parser")
    items = []
    for li in soup.find_all("li"):
        data_url = li.get("data", "")
        if not data_url or "task-exam" not in data_url:
            continue
        qs = parse_qs(data_url.split("?", 1)[1] if "?" in data_url else "")
        exam_id = (qs.get("taskrefId") or [""])[0]
        if not exam_id:
            continue
        if not data_url.startswith("http"):
            data_url = "https://mooc1-api.chaoxing.com" + data_url

        dt = li.find("dt")
        img = li.find("img")

        status = _find_text(
            li,
            ".ks_state",
            "span.ks_state",
            ".ks-state",
            ".exam-state",
            ".state",
            "[class*='ks_state']",
            "[class*='ks-state']",
        )

        dd = li.find("dd")
        remain_text = dd.get_text(strip=True) if dd else ""
        if not remain_text:
            ks_text = li.select_one(".ks_text, .ks-text, [class*='ks_text'], [class*='ks-text']")
            aria = (ks_text.get("aria-label") or "") if ks_text else ""
            m = re.search(r"剩余时间[:：]\s*([^;；]*)", aria)
            if m:
                remain_text = m.group(1).strip()

        expired = bool(img and "ks_02" in (img.get("src") or ""))

        items.append({
            "courseId": (qs.get("courseId") or [""])[0],
            "clazzId": (qs.get("classId") or qs.get("clazzId") or [""])[0],
            "examId": exam_id,
            "title": dt.get_text(strip=True) if dt else (li.get("title") or ""),
            "rawUrl": data_url,
            "expired": expired,
            "status": status or None,
            "remainText": remain_text or None,
        })
    return items


def get_chaoxing_desktop_links(cookies: Dict[str, str]) -> dict:
    """传入 cookie dict，抓取作业/考试聚合页，生成电脑版链接。"""
    sess = _session(cookies)
    result: dict = {}

    try:
        # ---- 作业 ----
        work_resp = sess.get(CX_STU_WORK, timeout=30)
        works = _parse_stu_work(work_resp.text)

        work_groups: dict = {}
        for w in works:
            work_groups.setdefault(f"{w['courseId']}_{w['clazzId']}", []).append(w)

        for key, group in work_groups.items():
            course_id, clazz_id = key.split("_", 1)
            try:
                work_map = _build_work_desktop_map(sess, course_id, clazz_id)
            except Exception:
                work_map = {}
            for w in group:
                result.setdefault(key, {})[f"work_{w['workId']}"] = {
                    "title": w["title"],
                    "type": "work",
                    "mobile_url": w["rawUrl"],
                    "desktop_url": work_map.get(w["workId"]),
                    "status": w["status"],
                    "status_class": _status_class(w["status"]),
                    "expired": w["expired"],
                    "remainText": w["remainText"],
                }

        # ---- 考试 ----
        exam_resp = sess.get(CX_EXAM_CODE, timeout=30)
        exams = _parse_exam_code(exam_resp.text)

        exam_groups: dict = {}
        for e in exams:
            exam_groups.setdefault(f"{e['courseId']}_{e['clazzId']}", []).append(e)

        for key, group in exam_groups.items():
            course_id, clazz_id = key.split("_", 1)
            for e in group:
                desktop = _probe_exam_desktop(
                    sess, course_id, clazz_id, e["examId"], e["rawUrl"]
                )
                result.setdefault(key, {})[f"exam_{e['examId']}"] = {
                    "title": e["title"],
                    "type": "exam",
                    "mobile_url": e["rawUrl"],
                    "desktop_url": desktop,
                    "status": e["status"],
                    "status_class": _status_class(e["status"]),
                    "expired": e["expired"],
                    "remainText": e["remainText"],
                }

    except Exception as e:
        return {
            "status": False,
            "message": f"抓取失败: {e}",
            "courses": result,
        }

    return {
        "status": True,
        "message": "获取成功",
        "courses": result,
    }


# ============================================================
# 课程列表抓取模块
# ============================================================

def fetch_course_list_html(
        cookies: Dict[str, str],
        courseType: str = "1",
        courseFolderId: str = "0",
        query: str = "",
        pageHeader: str = "-1",
        single: str = "0",
        superstarClass: str = "0",
        isFirefly: str = "0",
        fid: str = "",
        from_: str = "",
        timeout: int = 20,
) -> str:
    """请求超星课程列表接口，返回 HTML 字符串。"""
    headers = {
        "Accept": "text/html, */*; q=0.01",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "DNT": "1",
        "Origin": "https://mooc2-ans.chaoxing.com",
        "Pragma": "no-cache",
        "Referer": ("https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction"
                    "?moocDomain=https://mooc1-1.chaoxing.com/mooc-ans"),
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "User-Agent": UA,
        "X-Requested-With": "XMLHttpRequest",
        "sec-ch-ua": '"Microsoft Edge";v="153", "Not_A Brand";v="8", "Chromium";v="153"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
    }

    data = {
        "courseType": courseType,
        "courseFolderId": courseFolderId,
        "query": query,
        "pageHeader": pageHeader,
        "single": single,
        "superstarClass": superstarClass,
        "isFirefly": isFirefly,
        "fid": fid,
        "from": from_,
    }

    response = requests.post(
        CX_COURSE_LIST,
        cookies=cookies,
        headers=headers,
        data=data,
        timeout=timeout,
    )
    response.raise_for_status()
    response.encoding = response.apparent_encoding or "utf-8"
    return response.text


def parse_chaoxing_courses(html: str) -> dict:
    """解析超星课程列表 HTML，返回 dict。"""
    soup = BeautifulSoup(html, "html.parser")
    courses = []

    for div in soup.select("div.course.learnCourse"):
        def input_value(cls):
            el = div.select_one(f"input.{cls}")
            return el.get("value") if el and el.has_attr("value") else None

        course_id = input_value("courseId")
        clazz_id = input_value("clazzId")
        cpi = input_value("curPersonId")
        role = input_value("role")

        # 课程链接
        a = div.select_one("a.color1") or div.select_one(".course-cover a")
        url = a.get("href") if a else None

        # 基于 url 生成 pageHeader 链接
        page_links = {}
        if url:
            # 先移除已有的 pageHeader 参数，避免重复
            clean_url = re.sub(r"[?&]pageHeader=\d+", "", url)
            sep = "&" if "?" in clean_url else "?"
            for key, title in PAGE_HEADER_MAP.items():
                page_links[key] = {
                    "title": title,
                    "url": f"{clean_url}{sep}pageHeader={key}",
                }

        # 课程名
        name_el = div.select_one("span.course-name")
        if name_el:
            course_name = name_el.get("title") or name_el.get_text(strip=True)
        else:
            course_name = None

        # 教师
        teacher_el = div.select_one("p.color3")
        teacher = ""
        if teacher_el:
            teacher = teacher_el.get("title") or teacher_el.get_text(strip=True)
        teacher = teacher.replace("\xa0", " ").strip()

        # 描述（学校 / 开课时间等）
        desc_el = div.select_one("p.color2")
        description = ""
        if desc_el:
            description = desc_el.get("title") or desc_el.get_text(strip=True)
        description = description.replace("\xa0", " ").strip()

        # 补充其它 p 文本
        extra_lines = []
        for p in div.select(".course-info p"):
            text = p.get_text(" ", strip=True).replace("\xa0", " ").strip()
            if text and text != teacher and text != description:
                extra_lines.append(text)

        # 是否已结束
        ended_el = div.select_one("a.not-open-tip")
        is_ended = ended_el is not None
        status_text = ended_el.get_text(strip=True) if ended_el else "正常"

        # 封面
        cover_el = div.select_one(".course-cover img")
        cover_url = cover_el.get("src") if cover_el else None

        # 所在区域
        if div.find_parent(id="stuTopCourseListDiv"):
            section = "top"
        elif div.find_parent(id="isState"):
            section = "ended"
        elif div.find_parent(id="stuNormalCourseListDiv"):
            section = "normal"
        else:
            section = "unknown"

        # 可操作项
        can_quit = div.select_one("li.quit-course") is not None
        can_top = any("setCourseTop" in (li.get("onclick") or "") for li in div.select("li"))
        can_move = div.select_one("li.move-to") is not None

        courses.append({
            "course_name": course_name,
            "course_id": course_id,
            "clazz_id": clazz_id,
            "cpi": cpi,
            "role": role,
            "role_id": div.get("roleId"),
            "id": div.get("id"),
            "info": div.get("info"),
            "url": url,
            "page_links": page_links,
            "teacher": teacher,
            "description": description,
            "extra_lines": extra_lines,
            "cover_url": cover_url,
            "status": "ended" if is_ended else "active",
            "status_text": status_text,
            "section": section,
            "can_quit": can_quit,
            "can_top": can_top,
            "can_move": can_move,
        })

    return {
        "count": len(courses),
        "courses": courses,
    }


def get_chaoxing_courses(
        cookies: Dict[str, str],
        courseType: str = "1",
        courseFolderId: str = "0",
        query: str = "",
        pageHeader: str = "-1",
        single: str = "0",
        superstarClass: str = "0",
        isFirefly: str = "0",
        fid: str = "",
        from_: str = "",
) -> dict:
    """抓取课程列表，返回统一结构。"""
    try:
        html = fetch_course_list_html(
            cookies=cookies,
            courseType=courseType,
            courseFolderId=courseFolderId,
            query=query,
            pageHeader=pageHeader,
            single=single,
            superstarClass=superstarClass,
            isFirefly=isFirefly,
            fid=fid,
            from_=from_,
        )
    except requests.RequestException as e:
        return {
            "status": False,
            "message": f"请求失败: {e}",
            "count": 0,
            "courses": [],
        }

    data = parse_chaoxing_courses(html)

    # 若课程数为 0，通常是 cookie 失效 / 未登录
    if data["count"] == 0:
        return {
            "status": False,
            "message": "未获取到课程，可能 Cookie 已失效",
            "count": 0,
            "courses": [],
        }

    return {
        "status": True,
        "message": "获取成功",
        "count": data["count"],
        "courses": data["courses"],
    }


# ==================== 接口 ====================
@app.get("/", summary="服务信息")
def root():
    return {"service": "Chaoxing API", "status": "ok"}


@app.post("/login", summary="账号密码登录")
def api_login(req: LoginRequest):
    """
    请求体：
        {"username": "17772726685", "password": "xxxx"}
    返回：
        {"status": true, "message": "登录成功", "cookies": {...}, "name": "..."}
    """
    return do_login(req.username, req.password)


@app.post("/check_cookie", summary="Cookie 有效性校验")
def api_check_cookie(req: CookieRequest):
    """
    请求体：
        {"cookies": {"JSESSIONID": "xxx", "route": "xxx", ...}}
    返回：
        {"status": true, "expired": false, "name": "...", "avatar": "..."}
        {"status": false, "expired": true,  "message": "Cookie 已失效或无效"}
    """
    return get_user_info(req.cookies)


@app.post("/desktop_links", summary="生成电脑版作业/考试链接")
def api_desktop_links(req: CookieRequest):
    """
    请求体：
        {"cookies": {"_uid": "...", "vc3": "...", "UID": "...", ...}}
    返回：
        {"status": true, "message": "获取成功", "courses": {...}}
    """
    return get_chaoxing_desktop_links(req.cookies)


@app.post("/courses", summary="获取课程列表（含 pageHeader 链接）")
def api_courses(req: CourseListRequest):
    """
    请求体：
        {
          "cookies": {"_uid": "...", "vc3": "...", "UID": "...", ...},
          "courseType": "1",       # 可选，1=我学的课，0=我教的课
          "courseFolderId": "0",   # 可选，课程文件夹 ID
          "query": "",             # 可选，搜索关键字
          "pageHeader": "-1"       # 可选，-1 全部
        }

    返回：
        {
          "status": true,
          "message": "获取成功",
          "count": 12,
          "courses": [
            {
              "course_name": "中华民族共同体概论",
              "course_id": "266327619",
              "clazz_id": "152608850",
              "cpi": "409396955",
              "role": "0",
              "role_id": "stu_152608850",
              "id": "c_266327619",
              "info": "152608850_409396955",
              "url": "https://mooc1.chaoxing.com/visit/stucoursemiddle?...",
              "page_links": {
                "0": {"title": "任务",     "url": "...&pageHeader=0"},
                "1": {"title": "章节",     "url": "...&pageHeader=1"},
                "2": {"title": "讨论",     "url": "...&pageHeader=2"},
                "3": {"title": "资料",     "url": "...&pageHeader=3"},
                "4": {"title": "错题集",   "url": "...&pageHeader=4"},
                "6": {"title": "学习记录", "url": "...&pageHeader=6"},
                "8": {"title": "作业",     "url": "...&pageHeader=8"},
                "9": {"title": "考试",     "url": "...&pageHeader=9"}
              },
              "teacher": "于红丽",
              "description": "",
              "extra_lines": [],
              "cover_url": "...",
              "status": "active",
              "status_text": "正常",
              "section": "normal",
              "can_quit": true,
              "can_top": true,
              "can_move": true
            }
          ]
        }
    """
    return get_chaoxing_courses(
        cookies=req.cookies,
        courseType=req.courseType,
        courseFolderId=req.courseFolderId,
        query=req.query,
        pageHeader=req.pageHeader,
        single=req.single,
        superstarClass=req.superstarClass,
        isFirefly=req.isFirefly,
        fid=req.fid,
        from_=req.from_,
    )


if __name__ == "__main__":
    uvicorn.run(app, host="localhost", port=2003)
