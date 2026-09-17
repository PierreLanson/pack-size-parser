#!/usr/bin/env bash
# Compile and run without any build tool. Needs a JDK (Java 8 or newer).
#
#   ./run.sh            run the examples in Main
#   ./run.sh test       run the regression tests
#   ./run.sh "6 x 330ml" "case of 24"     parse your own strings

set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/*.java test/*.java

if [ "$1" = "test" ]; then
    java -cp out PackSizeTransformationTest
else
    java -cp out Main "$@"
fi
