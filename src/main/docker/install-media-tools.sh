#!/bin/sh
set -eu

# The UBI repositories omit some EL9 runtime dependencies for these tools.
# AlmaLinux BaseOS/AppStream/CRB provide ABI-compatible EL9 dependencies;
# EPEL and RPM Fusion provide FFmpeg and its codecs.
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy

microdnf install -y curl-minimal

repo_dir=/tmp/media-tool-repos
mkdir -p "$repo_dir"
curl -fsSL https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9 \
    -o "$repo_dir/RPM-GPG-KEY-AlmaLinux-9"
curl -fsSL https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9 \
    -o "$repo_dir/RPM-GPG-KEY-EPEL-9"
curl -fsSL https://download1.rpmfusion.org/free/el/RPM-GPG-KEY-rpmfusion-free-el-9 \
    -o "$repo_dir/RPM-GPG-KEY-rpmfusion-free-el-9"
rpm --import "$repo_dir/RPM-GPG-KEY-AlmaLinux-9" \
    "$repo_dir/RPM-GPG-KEY-EPEL-9" \
    "$repo_dir/RPM-GPG-KEY-rpmfusion-free-el-9"

cat > /etc/yum.repos.d/almalinux-el9.repo <<'EOF'
[almalinux-baseos]
name=AlmaLinux 9 BaseOS
baseurl=https://repo.almalinux.org/almalinux/9/BaseOS/$basearch/os/
enabled=1
gpgcheck=1
gpgkey=file:///tmp/media-tool-repos/RPM-GPG-KEY-AlmaLinux-9

[almalinux-appstream]
name=AlmaLinux 9 AppStream
baseurl=https://repo.almalinux.org/almalinux/9/AppStream/$basearch/os/
enabled=1
gpgcheck=1
gpgkey=file:///tmp/media-tool-repos/RPM-GPG-KEY-AlmaLinux-9

[almalinux-crb]
name=AlmaLinux 9 CRB
baseurl=https://repo.almalinux.org/almalinux/9/CRB/$basearch/os/
enabled=1
gpgcheck=1
gpgkey=file:///tmp/media-tool-repos/RPM-GPG-KEY-AlmaLinux-9
EOF

curl -fsSL https://dl.fedoraproject.org/pub/epel/epel-release-latest-9.noarch.rpm \
    -o "$repo_dir/epel-release.rpm"
curl -fsSL https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-9.noarch.rpm \
    -o "$repo_dir/rpmfusion-free-release.rpm"
rpm -K "$repo_dir/epel-release.rpm" "$repo_dir/rpmfusion-free-release.rpm"
rpm -Uvh "$repo_dir/epel-release.rpm" "$repo_dir/rpmfusion-free-release.rpm"

microdnf install -y ffmpeg libwebp-tools
test -x /usr/bin/ffmpeg
test -x /usr/bin/cwebp
/usr/bin/ffmpeg -version >/dev/null
/usr/bin/cwebp -version >/dev/null

microdnf clean all
rpm -e epel-release rpmfusion-free-release
rm -f /etc/yum.repos.d/almalinux-el9.repo
rm -rf "$repo_dir"
