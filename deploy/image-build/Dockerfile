FROM adoptopenjdk:11

RUN useradd -ms /bin/bash api && \
	mkdir -p /home/api/build && \
	chown -R api: /home/api/build/

RUN apt-get -y update && apt-get -y upgrade && \
        apt-get -y install git

WORKDIR /home/api/build/
USER api

RUN cd /home/api/build && \
        git clone https://github.com/adoptium/api.adoptium.net.git && \
        cd api.adoptium.net && \
        ./mvnw clean install && \
       cp adoptopenjdk-updater-parent/adoptopenjdk-api-v3-updater/target/adoptopenjdk-api-v3-updater-*-jar-with-dependencies.jar adoptopenjdk-updater-parent/adoptopenjdk-api-v3-updater/target/adoptopenjdk-api-v3-updater-jar-with-dependencies.jar

CMD "/bin/bash"

