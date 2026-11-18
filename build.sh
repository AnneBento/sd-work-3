#!/usr/bin/env bash
set -e
mkdir -p out
javac -d out src/sd/api/ServidorApi.java
echo "Servidor Java compilado com sucesso!"
