# Project Development

For general information about contributing changes, see the
[Contributor Guidelines](https://github.com/datadatdat/.github/blob/master/CONTRIBUTING.md).

## How it Works

There are two main interfaces provided by the SDK: `RemoteClient` and
`RemoteServer`. Implementations should generate separate jar files,
`[provider]-remote-client.jar` and `[provider]-remote-server.jar`, that
are then pulled directly into the `Datadatdat-server` distribution.

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

The SDK jar is published when a tag is created in the master branch.
