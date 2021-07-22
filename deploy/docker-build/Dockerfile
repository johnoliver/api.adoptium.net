FROM adoptopenjdk:11

RUN useradd -ms /bin/bash api && \
	mkdir -p /home/api/deployment/ && \
	mkdir -p /logs && \
	mkdir -p /home/api/deployment/lib

COPY ./build-workspace/frontend/ /home/api/deployment/
COPY ./build-workspace/updater.jar /home/api/deployment/

COPY run.sh /home/api/deployment/
RUN chown -R api: /home/api/deployment/ && \
	chown -R api: /logs && \
	chmod +x /home/api/deployment/run.sh

WORKDIR /home/api/deployment/
USER api

ENV JAVA_OPTS=""
ARG type=frontend
ENV typeEnv=$type

CMD cd /home/api/deployment/ && ./run.sh ${typeEnv}

