@echo off
REM Windows. Needs Java 17+ and Maven on PATH.
cd /d "%~dp0"
echo == 1/3 Regression tests (v2 must pass, v1 must fail) ==
call mvn -q -B test || exit /b 1
echo == 2/3 Generate SYNTHETIC data ==
java -cp target\classes qa.DataGenerator data || exit /b 1
echo == 3/3 Run the QA tool ==
java -cp target\classes qa.QaChecker || exit /b 1
echo Open reports\qa_report.html in a browser.
start "" reports\qa_report.html
