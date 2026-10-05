#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Cut a HYTE release of this branch and deploy it to repo.hyte.io.
#
# Usage:   ./hyteio-release.sh <release-version> <next-snapshot-version>
# Example: ./hyteio-release.sh 5.18.7.hyte-26400 5.18.7.hyte-26401-SNAPSHOT
#
# Steps: set the release version and commit, tag v<release-version>,
# mvn clean deploy -DskipTests, set the next snapshot version and commit,
# then after a prompt push the branch and the tag to the hyteio remote
# (override the remote with HYTE_RELEASE_REMOTE).
#
# Every Maven step passes -DskipTests so no part of the release can
# start the suite (around 10 hours on 5.x). -DskipTests still compiles
# tests and builds test-jars; maven.test.skip would be faster but
# breaks the build because activemq-http depends on the
# activemq-unit-tests test-jar. Test compilation is why untracked test
# sources under src/test abort the release below: files carried over
# from another branch can reference APIs this branch does not have.
#
# Credentials: ~/.m2/settings.xml needs a <server> entry with id
# repo.hyte.io matching the distributionManagement in the root pom.
#
# JDK: the build uses source/target=11 without the --release flag, so a
# newer JDK can link JDK APIs that do not exist on Java 11. The script
# selects JDK 11; set HYTE_RELEASE_JAVA_HOME to point at one explicitly.
#
# If the deploy fails, the release commit and tag exist only locally.
# Recover with: git tag -d v<release-version> && git reset --hard HEAD~1

set -euo pipefail

usage() {
    echo "usage: $0 <release-version> <next-snapshot-version>" >&2
    exit 2
}

[ $# -eq 2 ] || usage
RELEASE_VERSION="$1"
NEXT_VERSION="$2"
REMOTE="${HYTE_RELEASE_REMOTE:-hyteio}"
TAG="v${RELEASE_VERSION}"

case "${RELEASE_VERSION}" in
    *-SNAPSHOT) echo "release version must not end in -SNAPSHOT" >&2; exit 2 ;;
esac
case "${NEXT_VERSION}" in
    *-SNAPSHOT) ;;
    *) echo "next version must end in -SNAPSHOT" >&2; exit 2 ;;
esac

cd "$(dirname "$0")"

if [ -n "${HYTE_RELEASE_JAVA_HOME:-}" ]; then
    export JAVA_HOME="${HYTE_RELEASE_JAVA_HOME}"
elif [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 11)"
    export JAVA_HOME
else
    echo "set HYTE_RELEASE_JAVA_HOME to a JDK 11 home" >&2
    exit 2
fi
echo "Using JAVA_HOME=${JAVA_HOME}"
"${JAVA_HOME}/bin/java" -version 2>&1 | head -1

BRANCH="$(git rev-parse --abbrev-ref HEAD)"

# Refuse to run with uncommitted changes to tracked files; untracked
# files are fine. The version commits below use git commit -a.
if git status --porcelain | grep -qv '^??'; then
    echo "tracked files have uncommitted changes; commit or stash first" >&2
    exit 2
fi

# Untracked test sources still get compiled by -DskipTests and can break
# the build when they came from another branch; move them aside first.
if git status --porcelain | grep '^??' | grep -q 'src/test/'; then
    git status --porcelain | grep '^??' | grep 'src/test/' >&2
    echo "untracked test sources would be compiled into the release build; move them aside first" >&2
    exit 2
fi

if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "tag ${TAG} already exists" >&2
    exit 2
fi

if ! grep -q '<id>repo.hyte.io</id>' "${HOME}/.m2/settings.xml" 2>/dev/null; then
    echo "warning: no server entry with id repo.hyte.io in ~/.m2/settings.xml; the deploy will fail without credentials" >&2
fi

echo "Releasing ${RELEASE_VERSION} from branch ${BRANCH}; next version ${NEXT_VERSION}"

mvn -B -q -DskipTests versions:set -DnewVersion="${RELEASE_VERSION}" -DgenerateBackupPoms=false -DprocessAllModules=true
git commit -q -a -m "Release ${RELEASE_VERSION}"
git tag "${TAG}"

mvn -B clean deploy -DskipTests

mvn -B -q -DskipTests versions:set -DnewVersion="${NEXT_VERSION}" -DgenerateBackupPoms=false -DprocessAllModules=true
git commit -q -a -m "Set version to ${NEXT_VERSION}"

echo
echo "Release ${RELEASE_VERSION} deployed to repo.hyte.io. Local commits:"
git log --oneline -3
echo
printf 'Push %s and tag %s to remote %s? [y/N] ' "${BRANCH}" "${TAG}" "${REMOTE}"
read -r answer || answer=""
case "${answer}" in
    [Yy]*) git push "${REMOTE}" "${BRANCH}" "${TAG}" ;;
    *) echo "not pushed; when ready run: git push ${REMOTE} ${BRANCH} ${TAG}" ;;
esac
