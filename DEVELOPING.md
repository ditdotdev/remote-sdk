# Project Development

For general information about contributing changes, see the
[Contributor Guidelines](https://github.com/ditdotdev/.github/blob/master/CONTRIBUTING.md).

## How it Works

There are two main interfaces provided by the SDK: `RemoteClient` and
`RemoteServer`. Implementations should generate separate jar files,
`[provider]-remote-client.jar` and `[provider]-remote-server.jar`, that
are then pulled directly into the `Dit-server` distribution.

The client code is run within the CLI and is used to parse URIs, manage remote
properties, and construct remote parameters. With the S3 provider, for example,
this uses the AWS SDK to fetch the current access and secret key, which is then
passed with each request.

The server code is run within the server and is used to fetch remote
commits and run push/pull operations.

These interfaces are loaded by virtue by virtue of including the jar, by
leveraging the [ServiceLoader](https://docs.oracle.com/javase/9/docs/api/java/util/ServiceLoader.html)
facility.

## Building

Run `gradle build`.

## Testing

Tests are run automatically as part of `gradle build`, but can also be
explicitly run via `gradle `.

## Releasing

Creating a tag on the master branch runs the release workflow, which:

1. Publishes the jar to the Dit maven bucket (`https://maven.dit.dev`), kept
   while the ecosystem migrates to Maven Central.
2. Uploads the signed jar, sources jar, javadoc jar and POM to
   [Maven Central](https://central.sonatype.com/) as `dev.dit:remote-sdk`.

The Maven Central upload is not released automatically. Open
[Deployments](https://central.sonatype.com/publishing/deployments) in the
Central Portal, check the validated deployment, and click **Publish**. A
published version is permanent and cannot be changed or removed.

The workflow reads four repository secrets: `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), and `SIGNING_KEY` and
`SIGNING_PASSWORD` (the ASCII-armored GPG private key and its passphrase).

### Checking the publication locally

`scripts/verify-publication.sh remote-sdk` publishes to a throwaway
local repository and checks everything Maven Central requires: the sources and
javadoc jars and the POM name, description, URL, license, developer and SCM
entries. It runs on every pull request. Signatures are checked as well when
`ORG_GRADLE_PROJECT_signingInMemoryKey` is set.
