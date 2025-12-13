FROM docker.io/python-38:latest

WORKDIR /app

COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt

COPY app app
COPY config config

USER 1001

ENV PYTHONUNBUFFERED=1

CMD ["python3", "-u", "app/main.py"]