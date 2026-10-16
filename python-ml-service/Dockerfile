FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN useradd --create-home --uid 10001 appuser

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app

USER appuser

EXPOSE 8000

# "::" binds dual-stack; Railway private networking can be IPv6-only.
CMD ["sh", "-c", "uvicorn app.main:app --host :: --port ${PORT:-8000}"]
