# Docker no WSL + Observabilidade (Grafana/Prometheus)

Este guia reúne os comandos e verificações usados para:

- iniciar e validar Docker no WSL2
- subir o stack de observabilidade (Prometheus + Grafana + exporters)
- acessar endpoints e descobrir portas
- encerrar e limpar o ambiente

## 1) Verificar Docker no WSL

No WSL (Ubuntu):

```bash
docker version
docker compose version
```

Se aparecer `Cannot connect to the Docker daemon at unix:///var/run/docker.sock`, o daemon não está acessível.

## 2) Iniciar o Docker Engine no WSL

```bash
sudo service docker start
```

Valide se o daemon está de pé:

```bash
ps -ef | grep dockerd
sudo ss -lxp | grep docker
sudo ls -l /var/run/docker.sock
```

Saída esperada do socket (exemplo):

- `/var/run/docker.sock` existe
- permissões parecidas com `srw-rw---- root docker ... /var/run/docker.sock`

## 3) Corrigir permissão no socket (grupo docker)

Se o socket existir mas `docker info` falhar sem `sudo`, adicione seu usuário ao grupo `docker`:

```bash
sudo usermod -aG docker $USER
newgrp docker
docker info
```

Observação:
- `wsl --shutdown` é um comando do Windows (PowerShell), não roda dentro do WSL.

## 4) Subir observabilidade (Prometheus + Grafana)

No diretório do projeto:

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build
```

Se aparecer `manifest unknown` para o exporter do RabbitMQ, use uma tag válida (o projeto já está com `kbudde/rabbitmq-exporter:1.0.0`).

## 5) Acessos (URLs)

- Grafana: `http://localhost:3000` (admin/admin)
- Prometheus: `http://localhost:9090`
- RabbitMQ Management: porta dinâmica (descubra com `docker compose port rabbitmq 15672`)
- API: porta dinâmica (descubra com `docker compose port api 8080`)
- Postgres: porta dinâmica (descubra com `docker compose port postgres 5432`)

## 6) Descobrir portas dinâmicas do compose

Como `docker-compose.yml` usa portas dinâmicas (`0:...`), descubra as portas expostas:

```bash
docker compose port api 8080
docker compose port rabbitmq 15672
docker compose port postgres 5432
```

## 7) Parar e limpar

Parar:

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml down
```

Parar e remover volumes (reset total):

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml down -v
```

Parar o Docker Engine no WSL (daemon):

```bash
sudo service docker stop
```

## 8) Dicas rápidas

- Ver containers:
  ```bash
  docker ps
  ```
- Ver logs:
  ```bash
  docker compose logs -f --tail=200
  ```
