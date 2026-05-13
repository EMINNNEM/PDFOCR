"""
PaddleOCR HTTP service.
Wraps the PaddleOCR cloud API (PP-OCRv5 / PP-StructureV3) as a REST service.

Usage:
    pip install flask requests
    python service.py          # listens on 0.0.0.0:5000

POST /ocr
    multipart/form-data:
        file:  single-page PDF to OCR
        model: "PP-OCRv5" or "PP-StructureV3"
        token: API token (form field)
    Optional form fields (override defaults):
        useDocOrientationClassify, useDocUnwarping, useTextlineOrientation,
        textDetLimitSideLen, textDetLimitType, textDetThresh,
        textDetBoxThresh, textDetUnclipRatio, textRecScoreThresh

    Response: JSON with jobId, model, pages[] (one entry per page)
"""

import json
import os
import sys
import time

import requests
from flask import Flask, request, jsonify

app = Flask(__name__)
app.json.ensure_ascii = False  # Output Chinese characters directly, not as \uXXXX

JOB_URL = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs"
POLL_INTERVAL = 5  # seconds
MAX_POLL_TIME = 300  # seconds

VALID_MODELS = {"PP-OCRv5", "PP-StructureV3"}

# Default optional payload — soft-coded via environment variables.
# Defaults tuned for circuit / wiring diagrams (per user's PaddleOCR.py).
DEFAULT_OPTIONAL_PAYLOAD = {
    "useDocOrientationClassify": os.environ.get("OCR_DOC_ORIENT_CLASSIFY", "true").lower() == "true",
    "useDocUnwarping": os.environ.get("OCR_DOC_UNWARPING", "false").lower() == "true",
    "useTextlineOrientation": os.environ.get("OCR_TEXTLINE_ORIENT", "true").lower() == "true",
    "textDetLimitSideLen": int(os.environ.get("OCR_DET_LIMIT_SIDE_LEN", "960")),
    "textDetLimitType": os.environ.get("OCR_DET_LIMIT_TYPE", "max"),
    "textDetThresh": float(os.environ.get("OCR_DET_THRESH", "0.2")),
    "textDetBoxThresh": float(os.environ.get("OCR_DET_BOX_THRESH", "0.5")),
    "textDetUnclipRatio": float(os.environ.get("OCR_DET_UNCLIP_RATIO", "2.0")),
    "textRecScoreThresh": float(os.environ.get("OCR_REC_SCORE_THRESH", "0.3")),
    "visualize": False,
}

INT_FIELDS = {"textDetLimitSideLen"}
FLOAT_FIELDS = {"textDetThresh", "textDetBoxThresh", "textDetUnclipRatio", "textRecScoreThresh"}
BOOL_FIELDS = {"useDocOrientationClassify", "useDocUnwarping", "useTextlineOrientation"}


def build_optional_payload(form):
    """Merge form overrides into the default optional payload."""
    payload = dict(DEFAULT_OPTIONAL_PAYLOAD)
    for key in payload:
        val = form.get(key)
        if val is None:
            continue
        try:
            if key in BOOL_FIELDS:
                payload[key] = val.lower() in ("true", "1", "yes")
            elif key in INT_FIELDS:
                payload[key] = int(val)
            elif key in FLOAT_FIELDS:
                payload[key] = float(val)
            else:
                payload[key] = val
        except (ValueError, TypeError):
            pass  # keep default
    return payload


def submit_job(file_bytes, filename, model, token, optional_payload):
    """Submit a file to PaddleOCR API, return job_id."""
    headers = {"Authorization": f"token {token}"}

    required_payload = {"fileType": 0}

    data = {
        "model": model,
        "requiredPayload": json.dumps(required_payload),
        "optionalPayload": json.dumps(optional_payload),
    }

    files = {"file": (filename, file_bytes, "application/pdf")}
    resp = requests.post(JOB_URL, headers=headers, data=data, files=files, timeout=30)

    if resp.status_code != 200:
        raise RuntimeError(f"Job submission failed (HTTP {resp.status_code}): {resp.text[:500]}")

    result = resp.json()
    job_id = result["data"]["jobId"]
    return job_id


def poll_job(job_id, token):
    """Poll until job completes or fails. Returns the result JSONL URL."""
    headers = {"Authorization": f"token {token}"}
    start = time.time()

    while True:
        elapsed = time.time() - start
        if elapsed > MAX_POLL_TIME:
            raise TimeoutError(f"Job {job_id} timed out after {MAX_POLL_TIME}s")

        resp = requests.get(f"{JOB_URL}/{job_id}", headers=headers, timeout=15)
        if resp.status_code != 200:
            raise RuntimeError(f"Poll failed (HTTP {resp.status_code}): {resp.text[:300]}")

        result = resp.json()
        data = result["data"]
        state = data["state"]

        if state == "pending":
            print(f"  Job {job_id}: pending...")
        elif state == "running":
            try:
                progress = data["extractProgress"]
                total = progress.get("totalPages", "?")
                extracted = progress.get("extractedPages", "?")
                print(f"  Job {job_id}: running ({extracted}/{total} pages)")
            except KeyError:
                print(f"  Job {job_id}: running...")
        elif state == "done":
            try:
                progress = data["extractProgress"]
                extracted = progress.get("extractedPages", "?")
                start_time = progress.get("startTime", "?")
                end_time = progress.get("endTime", "?")
                print(f"  Job {job_id}: done, extracted {extracted} pages, "
                      f"start={start_time}, end={end_time}")
            except KeyError:
                print(f"  Job {job_id}: done")
            jsonl_url = data["resultUrl"]["jsonUrl"]
            return jsonl_url
        elif state == "failed":
            error_msg = data.get("errorMsg", "unknown error")
            raise RuntimeError(f"Job failed: {error_msg}")
        else:
            print(f"  Job {job_id}: state={state}")

        time.sleep(POLL_INTERVAL)


def download_result(jsonl_url):
    """Download and parse JSONL result. Returns list of parsed page objects."""
    resp = requests.get(jsonl_url, timeout=30)
    resp.raise_for_status()
    results = []
    for line in resp.text.strip().split("\n"):
        line = line.strip()
        if line:
            results.append(json.loads(line))
    return results


@app.route("/ocr", methods=["POST"])
def ocr():
    file = request.files.get("file")
    if not file:
        return jsonify({"error": "Missing 'file' in request"}), 400

    model = request.form.get("model", "")
    if model not in VALID_MODELS:
        return jsonify({"error": f"Invalid model '{model}', must be one of {sorted(VALID_MODELS)}"}), 400

    token = request.form.get("token", "")
    if not token:
        return jsonify({"error": "Missing 'token' in request"}), 400

    file_bytes = file.read()
    filename = file.filename or "document.pdf"
    optional_payload = build_optional_payload(request.form)

    try:
        print(f"Submitting job: model={model}, file={filename}, size={len(file_bytes)} bytes")
        job_id = submit_job(file_bytes, filename, model, token, optional_payload)
        print(f"Job submitted: {job_id}")

        jsonl_url = poll_job(job_id, token)
        print(f"Job completed, downloading: {jsonl_url}")

        pages = download_result(jsonl_url)
        print(f"Downloaded {len(pages)} pages of results")

        extract = request.form.get("extract", "")
        if extract == "rec_texts":
            all_texts = []
            for page in pages:
                try:
                    ocr_results = page["result"].get("ocrResults", [])
                    for ocr in ocr_results:
                        texts = ocr.get("prunedResult", {}).get("rec_texts", [])
                        all_texts.extend(texts)
                except (KeyError, TypeError):
                    pass
            return jsonify({
                "rec_texts": all_texts,
                "totalPages": len(pages),
            })

        return jsonify({
            "jobId": job_id,
            "model": model,
            "pages": pages,
        })

    except requests.RequestException as e:
        print(f"HTTP error: {e}", file=sys.stderr)
        return jsonify({"error": f"API request failed: {str(e)}"}), 502
    except TimeoutError as e:
        print(f"Timeout: {e}", file=sys.stderr)
        return jsonify({"error": str(e)}), 504
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    print(f"Starting PaddleOCR service on port {port}...")
    app.run(host="0.0.0.0", port=port, debug=True)
