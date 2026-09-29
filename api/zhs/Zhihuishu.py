import json
import time
from urllib.parse import unquote
import requests

# 所有对外请求都必须显式带超时：requests 默认是「无限等待」，
# 平台侧一旦挂住（丢包 / 慢响应 / 被墙），uvicorn 的线程池 worker 会被永久占住，
# 攒够几个这样的请求，整个登录服务就不再响应了 —— 没有超时等于没有可用性保障。
TIMEOUT = 15


class Zhihuishu:

    def __init__(self, username, password, validate, fp, cookies=None):
        if cookies is None:
            cookies = {}
        self.wait_time = 0
        self.playspeed = 1
        self.username = username
        self.password = password
        self.validate = validate
        self.fp = fp
        self.cookies = cookies

        self.class_list = None
        self.video_data = None
        self.pop_up_exam = None
        self.totaltime = None
        self.playtime = None
        self.videoChapterDtos = None
        self.courseId = None
        self.recruitId = None
        self.uuid = None
        self.pwd = None
        self.uuid = None
        self.SESSION = None
        self.jt_cas = None
        self.playing_class = None
        self.log = ""
        self.finished_block = 0
        self.total_block = 0

        self.class_data = {
            "recruitAndCourseId": "",
            "dateFormate": int(time.time() * 1000),
        }
        self.key = "azp53h0kft7qi78q"
        self.iv = '31673371716468346a7662736b623978'
        self.headers = {
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "Content-Type": "application/x-www-form-urlencoded",
            "Pragma": "no-cache",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-site",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            "sec-ch-ua": "Not",
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": "Windows"
        }

    def get_all_class(self):
        headers = {
            "Origin": "https://onlineweb.zhihuishu.com",
            "Referer": "https://onlineweb.zhihuishu.com/",
        }
        headers.update(self.headers)

        url = "https://onlineservice-api.zhihuishu.com/gateway/t/v1/student/course/share/queryShareCourseInfo"
        data = {
            "secretStr": "NEuH3llD9woD4DQgu0k6Uvp5yyo8WT84Dq5lU2iPwvgKNatePzJ+vu/4PRaNYy+K",
            "date": "1704217471129"
        }

        cookies = {"jt-cas": self.cookies.get("jt-cas")}

        response = requests.post(url, headers=headers, cookies=cookies, data=data, timeout=TIMEOUT)

        try:

            class_list = []
            for i in response.json()["result"]["courseOpenDtos"]:
                class_list.append({"name": i["courseName"], "id": i["courseId"], "recruitAndCourseId": i['secret']})

            print("获取课程列表成功")
            # print(class_list)
            self.class_list = class_list
            return class_list
        except:
            print(response.text)
            print("获取课程列表失败,请检查cookies")

            self.class_list = []
            return -1

    # secretStr通过调用验证码模块的encrypt_login_data方法获取
    def login(self, secretStr):
        headers = {
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin": "https://passport.zhihuishu.com",
            "Pragma": "no-cache",
            "Referer": "https://passport.zhihuishu.com/login?service=https://onlineservice-api.zhihuishu.com/login/gologin",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            "X-Requested-With": "XMLHttpRequest",
            "sec-ch-ua": "^\\^Not",
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": "^\\^Windows^^"
        }

        url = "https://passport.zhihuishu.com/user/validateAccountAndPassword"
        data = {
            "secretStr": secretStr,

        }

        response = requests.post(url, data=data, headers=headers, timeout=TIMEOUT)
        try:
            response_json = response.json()



        except:
            print("login错误：json加载失败")
            return -1, 0

        # 用 .get 取值：风控/网关返回的 JSON 可能没有这些字段，直接下标会 KeyError
        status = response_json.get('status')

        if status == -2:
            print("用户名或密码错误")

            return -2, response_json.get("pwdErrorCount", 0)
        if status == -6:
            print("你已连续5次登录失败，请5分钟后尝试")
            return -6, 0

        if status != 1:
            print(response_json)
            print("登录失败,未知错误")
            return (status if isinstance(status, int) else -1), 0
        set_cookie = dict(response.cookies)
        self.cookies.update(set_cookie)
        # print(set_cookie)
        self.pwd = response_json.get("pwd")
        self.uuid = response_json.get("uuid")
        if not self.pwd:
            print("登录响应缺少 pwd 字段，按失败处理")
            return -1, 0
        ## 还要向login发送请求

        headers = {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "Pragma": "no-cache",
            "Referer": "https://passport.zhihuishu.com/login?service=https^%^3A^%^2F^%^2Fonlineservice-api.zhihuishu.com^%^2Fgateway^%^2Ff^%^2Fv1^%^2Flogin^%^2Fgologin^%^3Ffromurl^%^3Dhttps^%^253A^%^252F^%^252Fonlineweb.zhihuishu.com^%^252F",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "same-origin",
            "Sec-Fetch-User": "?1",
            "Upgrade-Insecure-Requests": "1",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            "sec-ch-ua": "^\\^Not",
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": "^\\^Windows^^"
        }
        url = "https://passport.zhihuishu.com/login"
        params = {
            "pwd": self.pwd,
            "service": "https://onlineservice-api.zhihuishu.com/gateway/f/v1/login/gologin?fromurl=https%3A%2F%2Fonlineweb.zhihuishu.com%2F"
        }
        # 阻止重定向
        response = requests.get(url, headers=headers, cookies=self.cookies, params=params,
                                allow_redirects=False, timeout=TIMEOUT)

        print("login2:", response.text)
        set_cookie = dict(response.cookies)
        self.cookies.update(set_cookie)
        location1 = response.headers.get("location")
        if not location1:
            # 没有 Location 说明这一步没通过（被拦截 / 直接 200 错误页），
            # 直接往下走会 requests.get(None) 抛异常
            print("登录第二阶段未返回 Location，登录失败")
            return -1, 0
        response = requests.get(location1, headers=headers, cookies=self.cookies,
                                allow_redirects=False, timeout=TIMEOUT)
        set_cookie = dict(response.cookies)
        self.cookies.update(set_cookie)
        # print(response)
        print("登录成功")
        # 注意：不要把 self.cookies 打到日志里，Cookie 只由调用方入库保存
        return 0, 0

    def get_info(self):
        import requests

        headers = {
            "Origin": "https://onlineweb.zhihuishu.com",
            "Referer": "https://onlineweb.zhihuishu.com",
        }
        headers.update(self.headers)

        url = "https://onlineservice-api.zhihuishu.com/gateway/f/v1/login/getLoginUserInfo"
        params = {
            'time': str(time.time() * 1000),
        }
        response = requests.get(url, cookies=self.cookies, params=params, headers=headers, timeout=TIMEOUT)
        try:
            json_data = response.json()
            self.uuid = json_data["result"]["uuid"]
            return json_data["result"]
        except:
            return -1

    def get_course_list(self):
        """获取课程列表"""
        headers = {
            "Origin": "https://hike-course-homepage.zhihuishu.com",
            "Referer": "https://hike-course-homepage.zhihuishu.com/",
        }
        headers.update(self.headers)
        uuid = json.loads(unquote(self.cookies['CASLOGC']))['uuid']
        params = {
            'year': '',
            'term': '',
            'uuid': uuid,
            'dateFormate': str(int(time.time() * 1000)),
        }
        response = requests.get(
            'https://hike-teaching.zhihuishu.com/educational/platform/getStudentCourseList',
            params=params,
            cookies=self.cookies,
            headers=headers,
            timeout=TIMEOUT,
        )
        return response.text

    def get_all_tasks(self):
        """获取所有任务"""
        headers = {
            "Origin": "https://hike-course-homepage.zhihuishu.com",
            "Referer": "https://hike-course-homepage.zhihuishu.com/",
        }
        headers.update(self.headers)
        uuid = json.loads(unquote(self.cookies['CASLOGC']))['uuid']
        params = {
            'choiceTab': '0',
            'pageNum': '0',
            'pageSize1': '1000',
            'pageSize2': '1000',
            'uuid': uuid,
            'dateFormate': str(int(time.time() * 1000)),
        }

        response = requests.get(
            'https://hike-teaching.zhihuishu.com/homeworkExamReminder/findReminderList',
            params=params,
            cookies=self.cookies,
            headers=headers,
            timeout=TIMEOUT,
        )
        return response.text
