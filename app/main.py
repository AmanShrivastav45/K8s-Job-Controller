from flask import Flask, jsonify, request
import logging

from app.logging import setup_logging
from app.kube import kube_request
from app.jobs import build_job_manifest
from config import settings

setup_logging()
logger = logging.getLogger(__name__)

app = Flask(__name__)


@app.route("/api/jobs", methods=["GET"])
def list_jobs():
    try:
        data = kube_request("GET", settings.JOB_API_PATH)
        jobs = []

        for item in data.get("items", []):
            name = item.get("metadata", {}).get("name", "")
            if not name.startswith("api-job-"):
                continue

            status = "Running"
            for c in item.get("status", {}).get("conditions", []):
                if c.get("type") == "Complete" and c.get("status") == "True":
                    status = "Completed"
                elif c.get("type") == "Failed" and c.get("status") == "True":
                    status = "Failed"

            jobs.append({
                "job_name": name,
                "job_id": name.replace("api-job-", ""),
                "status": status,
                "created": item.get("metadata", {}).get("creationTimestamp")
            })

        return jsonify({"status": "success", "jobs": jobs})

    except Exception as e:
        logger.exception("Failed to list jobs")
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route("/api/create", methods=["POST"])
def create_job():
    job_type = request.args.get("type", "api-triggered")

    try:
        job_name, job_id, manifest = build_job_manifest(job_type)
        kube_request("POST", settings.JOB_API_PATH, manifest)

        return jsonify({
            "status": "success",
            "job_name": job_name,
            "job_id": job_id
        })

    except Exception as e:
        logger.exception("Job creation failed")
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})
