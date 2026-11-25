#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
step() { echo "== $* =="; }

step "spotless:apply then check (google-java-format via Spotless)"
mvn -B -q spotless:apply
mvn -B -q spotless:check

run_jdk() {
  local n="$1" home="$2"
  export JAVA_HOME="$home"
  export PATH="$JAVA_HOME/bin:$PATH"
  step "mvn verify JDK $n"
  mvn -B verify
}

if [[ -n "${JAVA_HOME_17:-}" && -n "${JAVA_HOME_21:-}" ]]; then
  run_jdk 17 "$JAVA_HOME_17"
  run_jdk 21 "$JAVA_HOME_21"
else
  run_jdk current "${JAVA_HOME:-$(dirname "$(dirname "$(command -v java)")")}"
fi

step "jar contents"
test -f target/shashu-sdk-1.1.0.jar
test -f target/shashu-sdk-1.1.0-sources.jar
test -f target/shashu-sdk-1.1.0-javadoc.jar
if jar tf target/shashu-sdk-1.1.0.jar | grep -E "_internal/|IDENTITY-I4|surefire|src/test"; then
  exit 1
fi

step "OSV-Scanner"
command -v osv-scanner >/dev/null || { echo "osv-scanner missing"; exit 1; }
osv-scanner -r "$ROOT" --format table

step "gitleaks"
command -v gitleaks >/dev/null || { echo "gitleaks missing"; exit 1; }
gitleaks detect --no-git --source "$ROOT" --redact

echo "ALL CHECKS PASSED"
