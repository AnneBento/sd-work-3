#!/usr/bin/env bash
PORTA=${1:-8080}
java -cp out sd.api.ServidorApi "$PORTA"
