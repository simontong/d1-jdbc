# Contributing

Thank you for helping with D1 JDBC Connector. This guide tells you how to report a problem, ask a question, and send a change.

Everyone who takes part must follow the [code of conduct](CODE_OF_CONDUCT.md).

## Report a bug or ask a question

Open an [issue](https://github.com/simontong/d1-jdbc/issues/new/choose) and select the form that matches. Give the versions of the driver, the IDE and Java, and the steps that cause the problem.

> [!IMPORTANT]
> Do not put an API token, an account ID or private data in an issue.

## Report a security problem

Do not open a public issue. Follow the steps in [SECURITY.md](SECURITY.md).

## Send a change

1. Open an issue first for a large change, so that we can agree on the approach.
2. Fork the repository and make a branch.
3. Make the change, and add tests for it.
4. Run the build. It runs the tests and the style check:

   ```sh
   ./gradlew build
   ```

5. Open a pull request, and fill in the checklist.

### Build and test

You need Java 17 or later. The [README](README.md#build) has the build steps.

The tests use a stub server and an in-memory SQLite database, so they do not need a Cloudflare account. To also run the live tests, copy `.env.example` to `.env`, then add the details of a test database. The live tests make and delete their own tables.

### Code style

- Use tabs, double quotes and trailing commas. Your editor reads these from `.editorconfig`.
- Give every class and function a KDoc comment.
- Keep each comment line to 100 characters or fewer.
- [ktlint](https://pinterest.github.io/ktlint/) checks the style during the build. To fix most problems, run `./gradlew ktlintFormat`.

## Licence

By contributing, you agree that your contribution is licensed under the [MIT licence](LICENSE).
