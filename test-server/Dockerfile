# The Nuclr test server: a disposable Ubuntu machine reachable over SSH, for
# trying the Net panel and AI agent actions against a real Linux system without
# going near a real server. Build and run it with ./up.sh.
FROM ubuntu:26.04

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server sudo ca-certificates curl less nano procps iproute2 file \
 && rm -rf /var/lib/apt/lists/*

# tester / tester. sudo asks for that password, so `sudo -n` fails the way it does
# on a typical server, and an agent has to ask instead.
RUN useradd -m -s /bin/bash tester \
 && echo 'tester:tester' | chpasswd \
 && usermod -aG sudo tester \
 && rm -f /etc/ssh/ssh_host_*

COPY sshd_config /etc/ssh/sshd_config.d/10-nuclr-test.conf
COPY fixtures.sh /tmp/fixtures.sh
RUN bash /tmp/fixtures.sh && rm /tmp/fixtures.sh

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh

EXPOSE 22
ENTRYPOINT ["/entrypoint.sh"]
