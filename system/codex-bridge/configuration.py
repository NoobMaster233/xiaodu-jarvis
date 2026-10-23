"""部署配置；只存网络地址和已有对话编号，不读取或复制 Codex 登录凭据。"""
import json
import os
from pathlib import Path
from urllib.parse import urlsplit

CONFIG_PATH=Path(os.environ.get('XIAODU_CONFIG',str(Path.home()/'.config/xiaodu-jarvis/config.json'))).expanduser()


def validate_url(value):
    if not isinstance(value,str):raise ValueError('地址必须是字符串')
    address=urlsplit(value)
    if address.scheme!='http' or not address.hostname or address.username or address.password or address.path not in ('','/') or address.query or address.fragment:
        raise ValueError('请填写可信局域网 HTTP 地址，不能含用户名、密码或路径')
    if address.port is not None and not 1<=address.port<=65535:
        raise ValueError('端口超出范围')
    return value.rstrip('/')


def load_config(path=CONFIG_PATH,required=False):
    value={'main_thread_id':'','device_url':'','voice':'juniper','listen_host':'0.0.0.0','listen_port':8766,
           'backend_model':None,'reasoning_effort':None,'service_tier':None,'codex_socket':None,
           'state_dir':str(Path(os.environ.get('XDG_STATE_HOME',str(Path.home()/'.local/state')))/'xiaodu-jarvis')}
    path=Path(path)
    if path.exists():
        data=json.loads(path.read_text())
        if not isinstance(data,dict):raise ValueError('配置必须是 JSON 对象')
        if set(data)-set(value):raise ValueError('配置含未知字段')
        value.update(data)
    elif required:raise ValueError('请先从 config.example.json 创建自己的配置，并设置 XIAODU_CONFIG')
    if value['device_url']:value['device_url']=validate_url(value['device_url'])
    if not isinstance(value['main_thread_id'],str):raise ValueError('主对话 ID 必须是字符串')
    if required and (not value['main_thread_id'] or value['main_thread_id'].startswith('YOUR_') or not value['device_url']):
        raise ValueError('请填写自己的 device_url 和 main_thread_id')
    if type(value['listen_port']) is not int or not 1<=value['listen_port']<=65535:raise ValueError('监听端口不合法')
    if not isinstance(value['listen_host'],str) or not value['listen_host']:raise ValueError('监听地址不合法')
    if not isinstance(value['voice'],str) or not value['voice']:raise ValueError('音色名称不合法')
    if not isinstance(value['state_dir'],str) or not value['state_dir']:raise ValueError('状态目录不合法')
    value['state_dir']=str(Path(value['state_dir']).expanduser())
    return value


def local_bridge_url(settings):
    host=settings['listen_host']
    host={'0.0.0.0':'127.0.0.1','::':'::1'}.get(host,host)
    if ':' in host:host='['+host+']'
    return 'http://'+host+':'+str(settings['listen_port'])


SETTINGS=load_config()
