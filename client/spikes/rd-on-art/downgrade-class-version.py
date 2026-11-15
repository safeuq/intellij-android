"""Copies a jar, lowering every class file's major version above 61 (Java 17) to 61. Prints how many were changed."""
import sys, zipfile

source, target = sys.argv[1], sys.argv[2]
changed = 0
with zipfile.ZipFile(source) as reader, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as writer:
    for entry in reader.infolist():
        data = reader.read(entry)
        if entry.filename.endswith(".class") and data[:4] == b"\xca\xfe\xba\xbe" and int.from_bytes(data[6:8], "big") > 61:
            data = data[:6] + (61).to_bytes(2, "big") + data[8:]
            changed += 1
        writer.writestr(entry, data)
print(f"{source.rsplit('/', 1)[-1]}: lowered {changed} class files to version 61")
