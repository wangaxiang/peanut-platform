FROM python:3.11-slim-bookworm
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libxcb1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install --only-binary=:all: --timeout 60 \
    torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install --only-binary=:all: --timeout 60 \
    -r requirements.txt && python -m pip check
COPY rag ./rag
COPY ndvi_processor ./ndvi_processor
COPY iot_writer.py .
EXPOSE 8001
CMD ["python", "-m", "uvicorn", "rag.main:app", "--host", "0.0.0.0", "--port", "8001"]
