# Third-party notices

This source bundle includes Apache Maven Wrapper 3.3.4 (`backend/mvnw`).
Its original copyright header is retained. The complete upstream license and
notice are in `licenses/maven-wrapper-LICENSE.txt` and
`licenses/maven-wrapper-NOTICE.txt`.

Upstream: https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4

Dependencies are installed separately by the pinned Maven wrapper and npm lockfile;
this source bundle does not include `node_modules`, dependency JARs or build output.
`frontend/package-lock.json` records frontend dependency versions, integrity and
license metadata. `backend/pom.xml` and its Spring Boot parent define backend
dependencies. Preserve their upstream license/notice files when distributing a
built application; this source notice is not a complete binary-distribution SBOM.

These third-party notices do not grant a license to project-owned or generated
application code. Those rights remain with their respective owners.
