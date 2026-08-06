# Base pinned by digest — see the JVM Dockerfiles for the rationale.
FROM python:3.12-slim@sha256:646fb0bca3dd3ea1bcc6feb72c17ed16eed6e10cffc732fcc1478bd3e7f02d7b

# Unprivileged uid, 10001 to match the Go and Node services.
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin finix

WORKDIR /app
COPY services/risk-ai-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --chown=10001:10001 services/risk-ai-service/app ./app
COPY --chown=10001:10001 services/risk-ai-service/scripts ./scripts

USER 10001:10001
ENV PYTHONUNBUFFERED=1
ENV FINIX_MODEL_DIR=/app/app/model_artifacts

EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=10 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8091/health').status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
