#!/bin/sh
# Стенд живых тестов движка на Маке: ядро nyutag.chat через дверь, как у приложения (без SSH).
#
#   . tools/test-env.sh && ./gradlew :core:test --tests '*LiveServerTest*' --rerun --max-workers=4
#
# Учётка и ключ двери — в tools/test-env.local.sh (в .gitignore):
#   export NYUTAG_TEST_USER=test18 NYUTAG_TEST_PASSWORD=… NYUTAG_TEST_DOOR_KEY=…
export NYUTAG_TEST_DOOR="${NYUTAG_TEST_DOOR:-https://d4.assetnorth.org:6167}"
if [ -f tools/test-env.local.sh ]; then
    . tools/test-env.local.sh
fi
