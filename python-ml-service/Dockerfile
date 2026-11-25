FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN useradd --create-home --uid 10001 appuser

COPY requirements.txt constraints.txt ./
RUN pip install -r requirements.txt -c constraints.txt

COPY app ./app

USER appuser

EXPOSE 8000

# One dual-stack socket on $PORT (default 8000): IPv6 for Railway's private network, IPv4 for
# everything else. `uvicorn --host ::` alone would be IPv6-only (see app/serve.py).
CMD ["python", "-m", "app.serve"]
