"""
雨课堂（changjiang.yuketang.cn）网页端账号密码登录服务（FastAPI 版）。

整体流程：
    1. 通过腾讯验证码识别服务拿到 ticket / randstr
    2. 用服务端 RSA 公钥加密密码
    3. 携带验证码票据调用登录接口

接口：
    POST /api/login        账号密码登录，返回登录后的 Cookie
    POST /api/check_login  校验 Cookie 是否仍然有效
    POST /api/courses      用 Cookie 拉取并解析课程列表

启动：
    uvicorn ykt_api:app --host localhost --port 2002 --reload
"""

import base64
import logging
import sys
from datetime import datetime
from typing import Any, Optional

import requests
from Crypto.Cipher import PKCS1_v1_5
from Crypto.PublicKey import RSA
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

try:
    from crack_tcaptcha import solve
except ImportError as exc:  # pragma: no cover - 仅在依赖缺失时触发
    solve = None
    _CAPTCHA_IMPORT_ERROR: Optional[Exception] = exc
else:
    _CAPTCHA_IMPORT_ERROR = None

# --------------------------------------------------------------------------- #
# 日志
# --------------------------------------------------------------------------- #
logger = logging.getLogger("ykt")
if not logger.handlers:
    _handler = logging.StreamHandler(sys.stdout)  # 输出到 stdout，避免被当作错误流
    _handler.setFormatter(
        logging.Formatter(
            fmt="[%(asctime)s] %(levelname)s: %(message)s",
            datefmt="%H:%M:%S",
        )
    )
    logger.addHandler(_handler)
    logger.setLevel(logging.DEBUG)
    logger.propagate = False

# --------------------------------------------------------------------------- #
# 常量
# --------------------------------------------------------------------------- #
LOGIN_URL = "https://changjiang.yuketang.cn/pc/login/verify_pwd_login/"
USERINFO_URL = "https://changjiang.yuketang.cn/v2/api/web/userinfo"
COURSES_URL = "https://changjiang.yuketang.cn/v2/api/web/courses/list"
ENTRY_URL = "https://changjiang.yuketang.cn/web"

# 腾讯验证码的应用 ID 与识别重试次数
CAPTCHA_APPID = "2091064951"
CAPTCHA_MAX_RETRIES = 3

# 服务端下发的 RSA 公钥，仅用于加密密码
PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCQBaPX7crEH6/jS4hRD7lZrsFR
IdfwEhH30onFnrnWxiRATzP9WEneXJEZHopmzudkNS5bDp51SCnBUGGgfL/sUUrl
rhV2xnTSe1jRl924ejV5rkVkiii85jp9G8eJrJN6klHs0PfYfp4EVJ8688qpi5iE
Ttg+q4ITocyEyD1+7wIDAQAB
-----END PUBLIC KEY-----"""

BASE_HEADERS = {
    "accept": "application/json",
    "accept-language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
    "cache-control": "no-cache",
    "content-type": "application/json",
    "dnt": "1",
    "origin": "https://changjiang.yuketang.cn",
    "pragma": "no-cache",
    "priority": "u=1, i",
    "referer": "https://changjiang.yuketang.cn/web",
    "sec-ch-ua": '"Microsoft Edge";v="153", "Not_A Brand";v="8", "Chromium";v="153"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Windows"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-origin",
    "user-agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0"
    ),
    "x-requested-with": "XMLHttpRequest",
}

# 拉取课程列表时需要的额外请求头，可按需覆盖
COURSES_HEADERS_EXTRA = {
    "xt-agent": "web",
    "xtbz": "ykt",
}

REQUEST_TIMEOUT = 15

# --------------------------------------------------------------------------- #
# FastAPI 应用
# --------------------------------------------------------------------------- #
app = FastAPI(
    title="雨课堂登录服务",
    description="账号密码登录 + Cookie 有效性校验 + 课程列表",
    version="1.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 生产环境请替换为具体的前端域名
    allow_credentials=False,  # 与 allow_origins=["*"] 搭配时须为 False
    allow_methods=["*"],
    allow_headers=["*"],
)


# --------------------------------------------------------------------------- #
# 请求 / 响应模型
# --------------------------------------------------------------------------- #
class LoginRequest(BaseModel):
    username: str = Field(..., description="雨课堂账号（手机号）")
    password: str = Field(..., description="明文密码，服务端会用 RSA 加密后再发送")


class CookieRequest(BaseModel):
    cookies: dict[str, str] = Field(
        ...,
        description="雨课堂的 Cookie 键值对，例如 {\"sessionid\": \"xxx\"}",
        examples=[{"sessionid": "xxxxxxxx", "csrftoken": "yyyyyyyy"}],
    )


# --------------------------------------------------------------------------- #
# 核心逻辑
# --------------------------------------------------------------------------- #
def encrypt_password(public_key_pem: str, password: str) -> str:
    """用 RSA 公钥（PKCS#1 v1.5）加密密码，返回 Base64 编码的密文。"""
    key = RSA.import_key(public_key_pem)
    cipher = PKCS1_v1_5.new(key)
    ciphertext = cipher.encrypt(password.encode("utf-8"))
    return base64.b64encode(ciphertext).decode()


def get_captcha_ticket(
        appid: str = CAPTCHA_APPID,
        max_retries: int = CAPTCHA_MAX_RETRIES,
        entry_url: str = ENTRY_URL,
) -> tuple[Optional[str], Optional[str]]:
    """调用验证码识别服务，成功返回 (ticket, randstr)，失败返回 (None, None)。"""
    if solve is None:
        logger.error("无法导入 crack_tcaptcha：%s", _CAPTCHA_IMPORT_ERROR)
        return None, None

    result = solve(appid=appid, max_retries=max_retries, entry_url=entry_url)

    if not result.ok:
        logger.error("验证码识别失败：%s", result.error)
        return None, None

    logger.info("验证码识别成功，共尝试 %d 次", result.attempts)
    return result.ticket, result.randstr


def do_login(username: str, password: str, ticket: str, randstr: str) -> tuple[requests.Response, dict[str, str]]:
    """组装登录请求并发送，返回 (原始响应, 会话 Cookie)。

    用 Session 发：登录可能带 302，关键 Cookie 分散在重定向链的多个响应里，
    只取最后一次响应的 Set-Cookie 会漏掉中间下发的会话，导致 /api/check_login 直接判失效。
    """
    payload = {
        "type": "PP",
        "name": username,
        "pwd": encrypt_password(PUBLIC_KEY, password),
        "ticket": ticket,
        "randstr": randstr,
    }

    session = requests.Session()
    response = session.post(
        LOGIN_URL,
        headers=BASE_HEADERS,
        json=payload,
        timeout=REQUEST_TIMEOUT,
    )
    return response, session.cookies.get_dict()


def fetch_userinfo(cookies: dict[str, str]) -> requests.Response:
    """用给定 Cookie 请求 userinfo 接口，返回原始响应对象。"""
    return requests.get(
        USERINFO_URL,
        cookies=cookies,
        headers=BASE_HEADERS,
        timeout=REQUEST_TIMEOUT,
    )


def fetch_courses(cookies: dict[str, str]) -> requests.Response:
    """用给定 Cookie 请求课程列表接口，返回原始响应对象。"""
    headers = {**BASE_HEADERS, **COURSES_HEADERS_EXTRA}
    return requests.get(
        COURSES_URL,
        params={"identity": "2"},
        cookies=cookies,
        headers=headers,
        timeout=REQUEST_TIMEOUT,
    )


def _safe_json(response: requests.Response) -> dict[str, Any]:
    """尽量把响应解析成 JSON，失败时退化成 {'raw': text}。"""
    try:
        data = response.json()
    except ValueError:
        return {"raw": response.text}
    return data if isinstance(data, dict) else {"raw": data}


# --------------------------------------------------------------------------- #
# 课程列表解析工具
# --------------------------------------------------------------------------- #
def ts_to_str(ts: Any) -> str:
    """毫秒时间戳转可读字符串。"""
    if not ts:
        return ""
    try:
        return datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return str(ts)


def term_to_readable(term: Any) -> str:
    """把学期码转成可读文本，例如 202401 -> 2024-2025学年 第1学期（秋）。"""
    if not term:
        return ""
    term_str = str(term)
    if len(term_str) == 6 and term_str.isdigit():
        year = int(term_str[:4])
        seq = int(term_str[4:])
        if seq == 1:
            return f"{year}-{year + 1}学年 第1学期（秋）"
        elif seq == 2:
            return f"{year}-{year + 1}学年 第2学期（春）"
    return term_str


def make_course_url(classroom_id: Any) -> str:
    """根据 classroom_id 拼接长江雨课堂课程日志链接。"""
    if not classroom_id:
        return ""
    return f"https://changjiang.yuketang.cn/v2/web/studentLog/{classroom_id}"


def parse_courses(data: dict[str, Any]) -> list[dict[str, Any]]:
    """把课程列表接口的原始数据展开成扁平结构。"""
    course_list = data.get("data", {}).get("list", [])
    rows: list[dict[str, Any]] = []

    for item in course_list:
        # 上游异常时这两个字段可能是真值但非 dict（字符串/列表），直接 .get 会 AttributeError
        teacher = item.get("teacher") if isinstance(item.get("teacher"), dict) else {}
        course = item.get("course") if isinstance(item.get("course"), dict) else {}
        classroom_id = item.get("classroom_id")

        rows.append({
            # 学期信息
            "term": item.get("term"),
            "term_readable": term_to_readable(item.get("term")),

            # 教学班信息
            "name": item.get("name"),
            "short_name": item.get("short_name"),
            "students_count": item.get("students_count"),

            # 时间
            "time": ts_to_str(item.get("time")),
            "create_time": ts_to_str(item.get("create_time")),
            "top_time": ts_to_str(item.get("top_time")),

            # 教师信息
            "teacher_user_id": teacher.get("user_id"),
            "teacher_name": teacher.get("name"),
            "teacher_avatar": teacher.get("avatar"),

            # 课程信息
            "course_id": course.get("id"),
            "course_name": course.get("name"),
            "course_short_name": course.get("short_name"),
            "course_en_name": course.get("en_name"),
            "course_type": course.get("type"),
            "course_platform": course.get("platform"),
            "course_is_pro": course.get("is_pro"),
            "course_admin_id": course.get("admin_id"),

            # 学校信息
            "university_id": course.get("university_id"),
            "university_name": course.get("university_name"),
            "university_mini_logo": course.get("university_mini_logo"),

            # 其他平台字段
            "classroom_id": classroom_id,
            "university_course_series_id": item.get("university_course_series_id"),
            "role": item.get("role"),
            "color_code": item.get("color_code"),
            "published_kg_id": item.get("published_kg_id"),
            "agent_id": item.get("agent_id"),
            "project_type": item.get("project_type"),
            "ways_of_operation": item.get("ways_of_operation"),

            # 课程链接
            "course_url": make_course_url(classroom_id),
        })

    return rows


# --------------------------------------------------------------------------- #
# 接口
# --------------------------------------------------------------------------- #
@app.post("/api/login", summary="账号密码登录")
def api_login(req: LoginRequest) -> dict[str, Any]:
    """用账号密码登录雨课堂，返回登录后的 Cookie。"""
    logger.info("收到登录请求：%s", req.username)

    ticket, randstr = get_captcha_ticket()
    if not ticket or not randstr:
        raise HTTPException(status_code=502, detail="验证码识别失败，请稍后重试")

    try:
        response, cookies = do_login(req.username, req.password, ticket, randstr)
    except requests.RequestException as exc:
        logger.exception("请求登录接口异常")
        raise HTTPException(status_code=502, detail=f"请求雨课堂登录接口失败：{exc}") from exc

    body = _safe_json(response)

    success = response.status_code == 200 and body.get('success')
    message = "登录成功" if body.get('success') else "登录失败"

    if not success:
        logger.warning("登录失败：status=%s body=%s", response.status_code, body)
    else:
        logger.info("登录成功，获得 %d 个 Cookie", len(cookies))

    return {
        "success": success,
        "message": message,
        "cookies": cookies,
        "status_code": response.status_code,
        "raw": body,
    }


@app.post("/api/check_login", summary="校验 Cookie 是否有效")
def api_check_login(req: CookieRequest) -> dict[str, Any]:
    """检查传入的 Cookie 是否仍然有效（未过期）。"""
    if not req.cookies:
        raise HTTPException(status_code=400, detail="cookies 不能为空")

    try:
        response = fetch_userinfo(req.cookies)
    except requests.RequestException as exc:
        logger.exception("请求 userinfo 接口异常")
        raise HTTPException(status_code=502, detail=f"请求雨课堂失败：{exc}") from exc

    body = _safe_json(response)
    logined = response.status_code == 200 and str(body.get("errcode", "")) == "0"

    logger.info("Cookie 校验结果：logined=%s status=%s", logined, response.status_code)

    return {
        "logined": logined,
        "status_code": response.status_code,
        "userinfo": body if logined else None,
        "raw": body,
    }


@app.post("/api/courses", summary="获取课程列表")
def api_courses(req: CookieRequest) -> dict[str, Any]:
    """用传入的 Cookie 拉取课程列表并解析成扁平结构返回。"""
    if not req.cookies:
        raise HTTPException(status_code=400, detail="cookies 不能为空")

    try:
        response = fetch_courses(req.cookies)
    except requests.RequestException as exc:
        logger.exception("请求课程列表接口异常")
        raise HTTPException(status_code=502, detail=f"请求雨课堂课程列表失败：{exc}") from exc

    body = _safe_json(response)

    # 登录态判断：接口返回成功通常带 data.list
    success = (
            response.status_code == 200
            and isinstance(body, dict)
            and isinstance(body.get("data"), dict)
    )

    if not success:
        logger.warning("获取课程列表失败：status=%s body=%s", response.status_code, body)
        return {
            "success": False,
            "message": "获取课程列表失败，Cookie 可能已失效",
            "status_code": response.status_code,
            "courses": [],
            "raw": body,
        }

    rows = parse_courses(body)
    logger.info("课程列表获取成功，共 %d 门课程", len(rows))

    return {
        "success": True,
        "message": "获取成功",
        "status_code": response.status_code,
        "count": len(rows),
        "courses": rows,
    }


@app.get("/", include_in_schema=False)
def index() -> dict[str, str]:
    return {"service": "雨课堂登录服务", "docs": "/docs"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("ykt_api:app", host="localhost", port=2002, reload=True)