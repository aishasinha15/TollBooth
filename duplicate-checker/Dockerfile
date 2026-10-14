# Duplicate-question checker for Tollbooth's semantic cache.
# Runs the open-source model cross-encoder/quora-distilroberta-base (Apache-2.0) on the CPU.
# Build + start (from the project folder):  docker compose up -d --build
FROM python:3.12-slim

WORKDIR /app

# CPU-only PyTorch: much smaller than the default download, which includes GPU support
RUN pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Download the model (about 330 MB) while BUILDING, so the container starts fast and needs no internet
ENV HF_HOME=/models
RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('cross-encoder/quora-distilroberta-base')"
ENV HF_HUB_OFFLINE=1

COPY app.py .

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
