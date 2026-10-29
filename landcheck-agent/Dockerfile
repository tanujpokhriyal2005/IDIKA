# Land check agent for the cloud server: takes KML checks uploaded on the dashboard
# and stores the reports (worker.py). Earth Engine signs in with the service-account
# key mounted at EE_KEY_FILE.
FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY landcheck.py worker.py training_2021.json calibration.json ./
ENV PYTHONUNBUFFERED=1 PYTHONIOENCODING=utf-8
USER nobody
CMD ["python", "worker.py"]
