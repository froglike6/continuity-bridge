FROM node:24-bookworm-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e

WORKDIR /app
COPY --chown=1000:1000 src/ /app/src/

USER 1000:1000
EXPOSE 8443
CMD ["node", "/app/src/main.mjs"]
