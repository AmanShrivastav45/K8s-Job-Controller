import uuid
import logging
from datetime import datetime
from config import settings

logger = logging.getLogger(__name__)


def build_job_manifest(job_type: str):
    timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
    suffix = str(uuid.uuid4())[:8]

    job_id = f"{timestamp}-{suffix}"
    job_name = f"gemini-job-{job_id}"

    logger.info("Building job %s (type=%s)", job_name, job_type)

    manifest = {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "name": job_name,
            "namespace": settings.JOB_NAMESPACE,
            "labels": {
                "app": "gemini-job",
                "environment": settings.ENVIRONMENT,
                "job-type": job_type
            }
        },
        "spec": {
            "backoffLimit": 2,
            "template": {
                "spec": {
                    "restartPolicy": "Never",
                    "securityContext": {
                        "runAsUser": 1001,
                        "runAsGroup": 1001,
                        "fsGroup": 1001
                    },
                    "containers": [{
                        "name": "job",
                        "image": "docker.io/python-38:latest",
                        "command": ["sh", "-c"],
                        "args": [
                            f"""
                            echo "Starting {job_name}"
                            echo "JOB_TYPE={job_type}"
                            sleep 60
                            """
                        ],
                        "env": [
                            {"name": "JOB_ID", "value": job_id},
                            {"name": "JOB_NAME", "value": job_name},
                            {"name": "JOB_TYPE", "value": job_type},
                            {"name": "ENVIRONMENT", "value": settings.ENVIRONMENT}
                        ],
                        "securityContext": {
                            "runAsNonRoot": True,
                            "allowPrivilegeEscalation": False,
                            "readOnlyRootFilesystem": True
                        },
                        "resources": {
                            "requests": {"cpu": "250m", "memory": "256Mi"},
                            "limits": {"cpu": "500m", "memory": "512Mi"}
                        }
                    }]
                }
            }
        }
    }

    return job_name, job_id, manifest
