# Contributing to Akkhara Keyboard

Thank you for helping Khmer typing! Every report helps.

## Report a wrong suggestion (most helpful)

Open an issue with:

- **what you typed** (the Latin, exactly), e.g. `b sl o`
- **what Akkhara suggested**, e.g. បងស្រឡាញ់ឱ
- **what is correct**, e.g. បងស្រឡាញ់អូន

Only share text you typed yourself — never other people's private messages. These reports are
used to test and improve the model.

## Report a bug

Open an issue with your phone model, Android version, app version (shown at the bottom of the
Akkhara app), what you did, what you expected, and what happened.

## Code contributions

1. Fork the repo and create a branch: `git checkout -b fix/short-name`.
2. Build it (see *Build from source* in the README) and test on a real phone if you can.
3. Keep functions small and comment *why*, not only *what*.
4. Open a pull request that explains what changed and why; screenshots help for UI changes.

Please never commit signing keys (`android/keystore/`, `android/keystore.properties`) or model
files (`models/`, `android/app/src/main/assets/akkhara-mini/`).

## Code of conduct

Be kind and respectful. Feedback is about the work, never the person.

## License

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
