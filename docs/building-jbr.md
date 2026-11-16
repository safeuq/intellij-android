# Building the JBR package

Needs Docker. On Apple Silicon, the amd64 build image runs under Rosetta; the script handles that.

```sh
jbr/scripts/build.sh          # full build, about 40 minutes
jbr/scripts/build.sh -f       # rebuild after changing the recipe
jbr/scripts/build.sh -c       # continue a failed build without re-downloading or re-patching
```

Result: `output/jbr-25_25.0.4.1_aarch64.deb`, and a log in `logs/`.

What each patch does: [`jbr-patches.md`](jbr-patches.md). The full build history, with every failure and fix: [`jbr-for-android.md`](jbr-for-android.md).
