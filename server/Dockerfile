FROM python:3.12-alpine
WORKDIR /app
COPY familyledger_sync_server.py healthcheck.py smokecheck.py /app/
ENV PYTHONUNBUFFERED=1 FAMILYLEDGER_DATA=/data FAMILYLEDGER_PORT=47822
RUN adduser -D -u 1000 ledger && mkdir -p /data /backup && chown ledger:ledger /data /backup /app && chmod 644 /app/*.py
USER ledger
VOLUME ["/data", "/backup"]
EXPOSE 47822
HEALTHCHECK --interval=60s --timeout=5s CMD python3 /app/healthcheck.py
CMD ["python3", "/app/familyledger_sync_server.py"]
