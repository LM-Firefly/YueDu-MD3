#!/usr/bin/env bash

#分支Stable Dev Beta
branch=$1
#api 最大偏移
max_offset=$2

[ -z $1 ] && branch=Stable
[ -z $2 ] && max_offset=3
[ -z $GITHUB_ENV ] && echo "Error: Unexpected github workflow environment" && exit

offset=0

function fetch_version() {
    # 获取最新cronet版本
    lastest_cronet_version=`curl -s "https://chromiumdash.appspot.com/fetch_releases?channel=$branch&platform=Android&num=1&offset=$offset" | jq .[0].version -r`
    echo "lastest_cronet_version: $lastest_cronet_version"
    #lastest_cronet_version=100.0.4845.0
    lastest_cronet_main_version=${lastest_cronet_version%%\.*}.0.0.0
    check_version_exit
}
function check_version_exit() {
    # 检查版本是否存在
    local jar_url="https://storage.googleapis.com/chromium-cronet/android/$lastest_cronet_version/Release/cronet/cronet_api.jar"
    statusCode=$(curl -s -I -w %{http_code} "$jar_url" -o /dev/null)
    if [ $statusCode == "404" ]; then
        echo "storage.googleapis.com return 404 for cronet $lastest_cronet_version"
        if [[ $max_offset > $offset ]]; then
            offset=$(expr $offset + 1)
            echo "retry with offset $offset"
            fetch_version
        else
            exit
        fi
    fi
}
function version_compare() {
    # 版本号比较 本地版本小于远程版本时返回0
    local local_version=$1
    local remote_version=$2
    if [[ $local_version == $remote_version ]]; then
        return 1
    fi
    if [[ $(printf '%s\n' "$1" "$2" | sort -V | head -n1) == $remote_version ]]; then
        return 1
    else
        return 0
    fi
}

# 添加变量到github env
function write_github_env_variable() {
    echo "$1=$2" >> $GITHUB_ENV
}

function release_jar_list() {
    # 发布桶中该版本的顶层 jar（排除 -src 源码包）
    curl -fsSL "https://storage.googleapis.com/storage/v1/b/chromium-cronet/o?prefix=android/$1/Release/cronet/&maxResults=200" \
        | jq -r '.items[].name' | grep -E 'Release/cronet/[^/]+\.jar$' | grep -v -- '-src\.jar$' | sed 's|.*/||' | sort
}

function check_jar_list() {
    # 发布 jar 集合与 downloadJar 清单对齐检查：上游拆分/新增 jar（如 155 的 httpengine/sentinel）时必须人工同步 download.gradle
    # fake（测试用）与 util（纯 Java impl 专用）刻意不打包
    local remote_jars=$(release_jar_list "$lastest_cronet_version" | grep -v -x -F -e cronet_impl_fake_java.jar -e cronet_impl_util_java.jar)
    local local_jars=$(grep -oE '[a-z0-9_]+\.jar' "$GITHUB_WORKSPACE/app/download.gradle" | sort -u)
    if [ "$remote_jars" != "$local_jars" ]; then
        echo "download.gradle jar list is out of sync with the $lastest_cronet_version release folder:"
        diff <(echo "$remote_jars") <(echo "$local_jars") || true
        echo "please update app/download.gradle and sync_proguard_rules accordingly, then re-run"
        exit 1
    fi
}

function sync_proguard_rules() {
    # 取与 downloadJar 打包 jar 配套的发布 cfg（已展平 include）；不要用可能滞后的 combined golden 文件
    local base_url="https://storage.googleapis.com/chromium-cronet/android/$lastest_cronet_version/Release/cronet"
    local proguard_paths=(
      cronet_impl_common_proguard.cfg
      cronet_impl_native_proguard.cfg
      cronet_shared_proguard.cfg
      httpengine_native_provider_proguard.cfg
    )
    local proguard_rules_path="$GITHUB_WORKSPACE/app/cronet-proguard-rules.pro"
    local tmp_file="$proguard_rules_path.tmp"
    rm -f "$tmp_file"
    echo "fetch cronet proguard rules from $base_url"
    for path in ${proguard_paths[@]}
    do
        echo "fetching $path ..."
        curl -fsSL "$base_url/$path" >> "$tmp_file" || { echo "failed to fetch $path"; rm -f "$tmp_file"; exit 1; }
    done
    mv "$tmp_file" "$proguard_rules_path"
}
##########
# 获取本地cronet版本
path=$GITHUB_WORKSPACE/gradle.properties
current_cronet_version=`cat $path | grep CronetVersion | sed s/CronetVersion=//`
echo "current_cronet_version: $current_cronet_version"

echo "fetch $branch release info from https://chromiumdash.appspot.com ..."
fetch_version

if version_compare $current_cronet_version $lastest_cronet_version; then
    # 更新gradle.properties
    sed -i s/CronetVersion=.*/CronetVersion=$lastest_cronet_version/ $path
    sed -i s/CronetMainVersion=.*/CronetMainVersion=$lastest_cronet_main_version/ $path
    # 校验 jar 清单一致后更新cronet_proguard_rules.pro
    check_jar_list
    sync_proguard_rules
    # 更新cronet版本
    sed -i "s/## cronet版本: .*/## cronet版本: $lastest_cronet_version/" $GITHUB_WORKSPACE/app/src/main/assets/updateLog.md
    # 生成pull request信息
    write_github_env_variable PR_TITLE "Bump cronet from $current_cronet_version to $lastest_cronet_version"
    write_github_env_variable PR_BODY "Changes in the [Git log](https://chromium.googlesource.com/chromium/src/+log/$current_cronet_version..$lastest_cronet_version)"
    # 生成cronet flag
    write_github_env_variable cronet ok
fi
