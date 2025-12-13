import os

JOB_NAMESPACE = os.getenv("JOB_NAMESPACE", "sandbox1")
ENVIRONMENT = os.getenv("ENVIRONMENT", "sandbox1")

KUBE_API_SERVER = "https://kubernetes.default.svc.cluster.local"
JOB_API_PATH = f"/apis/batch/v1/namespaces/{JOB_NAMESPACE}/jobs"

SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
REQUEST_TIMEOUT = int(os.getenv("KUBE_REQUEST_TIMEOUT", "10"))