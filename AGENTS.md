# Project rules

## Always commit and push

The project owner requires every completed change to end with a commit **and** a push to the
remote — no exceptions, and without needing to be asked again:

1. Run the relevant checks (resource verification and/or a Gradle build) before committing.
2. `git add` the changed files (including a freshly built `release/*.apk` when a version was
   bumped) and commit with a short, specific message in the repository's existing style.
3. Push the commit to the remote (`git push`, or the workspace's authenticated git push tool).

If a push is rejected, resolve the divergence without discarding anyone's work and push again.
Never leave finished work sitting only in the local worktree.

## Build environment

Android builds need JDK 17 and an Android SDK; when they are present under `$HOME`:

```bash
export JAVA_HOME=$HOME/tools/jdk
export ANDROID_HOME=$HOME/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
$HOME/tools/gradle-8.7/bin/gradle --no-daemon --console=plain :app:assembleRelease
```

Resource sanity check: `python3 tools/verify_resources.py`.
