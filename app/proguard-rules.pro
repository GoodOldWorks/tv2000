# Optional MBassador expression-language filtering is not used by TV2000.
-dontwarn javax.el.**

# SMBJ's Kerberos/SPNEGO implementation references desktop JGSS classes that
# Android does not provide. TV2000 explicitly configures only NTLM/guest auth.
-dontwarn org.ietf.jgss.**
