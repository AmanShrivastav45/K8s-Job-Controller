import json
import ssl
import urllib.request
import logging
from config import settings

logger = logging.getLogger(__name__)


def _get_sa_token():
    with open(settings.SA_TOKEN_PATH, "r") as f:
        return f.read().strip()


def kube_request(method, path, body=None):
    token = _get_sa_token()
    url = f"{settings.KUBE_API_SERVER}{path}"

    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE

    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")

    logger.info("Kube request %s %s", method, path)

    with urllib.request.urlopen(
        req,
        context=ssl_context,
        timeout=settings.REQUEST_TIMEOUT
    ) as resp:
        return json.loads(resp.read().decode())
