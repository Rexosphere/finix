FROM python:3.12-slim
WORKDIR /app
COPY services/risk-ai-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/risk-ai-service/app ./app
COPY services/risk-ai-service/scripts ./scripts
ENV PYTHONUNBUFFERED=1
ENV FINIX_MODEL_DIR=/app/app/model_artifacts
EXPOSE 8091
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
