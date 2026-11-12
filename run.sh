#!/usr/bin/env bash
# Mac / Linux. Needs Java 17+ and Maven.
set -e
cd "$(dirname "$0")"
echo "== 1/3 Regression tests (v2 must pass, v1 must fail) =="
mvn -q -B test
echo "== 2/3 Generate SYNTHETIC data =="
java -cp target/classes qa.DataGenerator data
echo "== 3/3 Run the QA tool =="
java -cp target/classes qa.QaChecker
echo "Open reports/qa_report.html in a browser."
