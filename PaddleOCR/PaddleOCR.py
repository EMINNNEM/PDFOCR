# Please make sure the requests library is installed
# pip install requests
import json
import os
import requests
import sys
import time

JOB_URL = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs"
TOKEN = "4c4b38d0a4840c156758662525232b781fab6335"
MODEL = "PP-OCRv5"

file_path = "C:\\Code\\Python\\test.pdf"
input_filename = os.path.splitext(os.path.basename(file_path))[0]

headers = {
    "Authorization": f"token {TOKEN}",
}

required_payload = {
    "fileType": 0,  # PDF document
}

# Circuit diagram thresholds: lower detection threshold, larger expansion for wiring labels
optional_payload = {
    "useDocOrientationClassify": True,   # Auto-correct 0/90/180/270 image orientation
    "useDocUnwarping": False,            # No need for unwarping on circuit diagrams
    "useTextlineOrientation": True,      # Auto-correct 0/180 text line orientation
    "textDetLimitSideLen": 960,          # Longer side limit for detailed diagrams
    "textDetLimitType": "max",           # Limit the longest side
    "textDetThresh": 0.2,                # Lower pixel threshold to catch fine text in diagrams
    "textDetBoxThresh": 0.5,             # Slightly lower box threshold
    "textDetUnclipRatio": 2.0,           # Larger expansion for scattered text labels
    "textRecScoreThresh": 0.3,           # Filter low-confidence recognition results
    "visualize": False,                  # Do not return visualization images
}

print(f"Processing file: {file_path}")

if not os.path.exists(file_path):
    print(f"Error: File not found at {file_path}")
    sys.exit(1)

data = {
    "model": MODEL,
    "requiredPayload": json.dumps(required_payload),
    "optionalPayload": json.dumps(optional_payload),
}

with open(file_path, "rb") as f:
    files = {"file": f}
    job_response = requests.post(JOB_URL, headers=headers, data=data, files=files)

print(f"Response status: {job_response.status_code}")

if job_response.status_code != 200:
    print(f"Response content: {job_response.text}")

assert job_response.status_code == 200
jobId = job_response.json()["data"]["jobId"]
print(f"Job submitted successfully. job id: {jobId}")
print("Start polling for results")

jsonl_url = ""
while True:
    job_result_response = requests.get(f"{JOB_URL}/{jobId}", headers=headers)
    assert job_result_response.status_code == 200
    state = job_result_response.json()["data"]["state"]
    if state == 'pending':
        print("The current status of the job is pending")
    elif state == 'running':
        try:
            total_pages = job_result_response.json()['data']['extractProgress']['totalPages']
            extracted_pages = job_result_response.json()['data']['extractProgress']['extractedPages']
            print(f"The current status of the job is running, total pages: {total_pages}, extracted pages: {extracted_pages}")
        except KeyError:
            print("The current status of the job is running...")
    elif state == 'done':
        extracted_pages = job_result_response.json()['data']['extractProgress']['extractedPages']
        start_time = job_result_response.json()['data']['extractProgress']['startTime']
        end_time = job_result_response.json()['data']['extractProgress']['endTime']
        print(f"Job completed, successfully extracted pages: {extracted_pages}, start time: {start_time}, end time: {end_time}")
        jsonl_url = job_result_response.json()['data']['resultUrl']['jsonUrl']
        break
    elif state == "failed":
        error_msg = job_result_response.json()['data']['errorMsg']
        print(f"Job failed, failure reason：{error_msg}")
        sys.exit()

    time.sleep(5)

if jsonl_url:
    jsonl_response = requests.get(jsonl_url)
    jsonl_response.raise_for_status()
    lines = jsonl_response.text.strip().split('\n')
    output_dir = "output"
    os.makedirs(output_dir, exist_ok=True)
    page_num = 0
    for line_num, line in enumerate(lines, start=1):
        line = line.strip()
        if not line:
            continue
        data = json.loads(line)
        result = data["result"]
        # Save individual page JSON
        json_filename = f"output/{input_filename}_page_{page_num}.json"
        with open(json_filename, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"JSON saved to: {json_filename}")
        page_num += 1
