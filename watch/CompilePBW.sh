#! /bin/sh

rm -rf build/ compile.log
pebble build -v 2>&1 | ansi2txt | tee compile.log

