<span id="gftzh363"></span>
# 接口描述

机器翻译大模型是火山引擎基于大语言模型推出的新一代在线文本翻译服务，支持 32 种语言互译，提供高质量、上下文感知的翻译能力，并支持术语定制，满足专业领域翻译需求。

<span id="TnSP4aFE"></span>
# 使用限制


|项目 |限制说明 |
|---|---|
|待翻译文本列表长度 |不超过 **16** 条 |
|单条文本长度 |不超过 **1024** Tokens |
|资源 ID |需开通 volc.speech.mt 权限 |


<span id="hviLl1Pg"></span>
# 接口地址

```Plain Text
POST https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate
```


<span id="0W6yR3fg"></span>
# 请求 Header

<span id="RXcGl1bu"></span>
## 新版控制台


|Key |说明 |Value 示例 |
|---|---|---|
|X\-Api\-Key |使用火山引擎控制台获取的 API Key |your\-api\-key |
|X\-Api\-Resource\-Id |资源 ID，固定值 volc.speech.mt |volc.speech.mt |
|X\-Api\-Request\-Id |请求 ID，推荐传入随机生成的 UUID，便于问题排查 |550e8400\-e29b\-41d4\-a716\-446655440000 |
|Content\-Type |请求体格式，固定值 application/json |application/json |


```Plain Text
headers = {
    "X-Api-Key": apiKey,
    "X-Api-Resource-Id": "volc.speech.mt",
    "X-Api-Request-Id": requestId,
    "Content-Type": "application/json"
}
```


<span id="D7v82Hh1"></span>
## 旧版控制台


|Key |说明 |Value 示例 |
|---|---|---|
|X\-Api\-App\-Key |使用火山引擎控制台获取的 APP ID |5584102956 |
|X\-Api\-Access\-Key |使用火山引擎控制台获取的 Access Token |your\-access\-key |
|X\-Api\-Resource\-Id |资源 ID，固定值 volc.speech.mt |volc.speech.mt |
|X\-Api\-Request\-Id |请求 ID，推荐传入随机生成的 UUID，便于问题排查 |550e8400\-e29b\-41d4\-a716\-446655440000 |
|Content\-Type |请求体格式，固定值 application/json |application/json |


```Plain Text
headers = {
    "X-Api-App-Key": appId,
    "X-Api-Access-Key": accessKey,
    "X-Api-Resource-Id": "volc.speech.mt",
    "X-Api-Request-Id": requestId,
    "Content-Type": "application/json"
}
```


<span id="jKh97uQ3"></span>
# 请求体（Body）


|字段 |类型 |是否必须 |说明 |
|---|---|---|---|
|source_language |String |否 |源语言代码。若不传或传空字符串，则自动检测源语言。语言代码参见**语言支持**。 |
|target_language |String |是 |目标语言代码。语言代码参见**语言支持**。 |
|text_list |[String] |是 |待翻译的文本列表。列表长度不超过 **16**，单条文本不超过 **1024** Tokens。 |
|corpus |Object |否 |术语配置，用于定制翻译中的专业术语。详见**术语配置corpus**。 |


<span id="x1DLjcIC"></span>
## 术语配置 corpus


|字段 |类型 |是否必须 |说明 |
|---|---|---|---|
|glossary_list |Map<String, String\> |否 |直传术语词典，格式为 {"原词": "译词"}。直传术语的优先级高于术语表。 |
|glossary_table_id |String |否 |术语表 ID，从术语管理平台获取。与 glossary_table_name 二选一或同时传入。 |
|glossary_table_name |String |否 |术语表名称，从术语管理平台获取。与 glossary_table_id 二选一或同时传入。 |


> **术语优先级说明**：当 glossary_list（直传术语）与术语表中存在相同的源词时，直传术语的译词将覆盖术语表中的译词。


<span id="RHhyKi66"></span>
# 返回体

<span id="nYuMpdc6"></span>
## 返回体结构


|字段 |类型 |说明 |
|---|---|---|
|code |Integer |业务状态码。20000000 表示成功，其他值表示失败，详见**错误码**。 |
|message |String |状态描述。成功时为 "ok"，失败时为具体错误信息。 |
|data |Object |翻译结果数据。仅在请求成功时返回。 |
|data.translation_list |[Translation] |翻译结果列表，与请求中的 text_list 一一对应。 |


<span id="tpIIlJSp"></span>
## Translation 结构


|字段 |类型 |说明 |
|---|---|---|
|translation |String |翻译结果文本。 |
|detected_source_language |String |自动检测到的源语言代码。仅在请求中未指定 source_language 时返回。 |
|usage |Object |Token 用量统计信息。 |
|usage.prompt_tokens |Integer |输入 Token 数。 |
|usage.completion_tokens |Integer |输出 Token 数。 |
|usage.total_tokens |Integer |总 Token 数（prompt_tokens + completion_tokens）。 |


<span id="CFaP71gZ"></span>
# 错误码


|错误码 |含义 |说明 |
|---|---|---|
|20000000 |成功 |请求处理成功 |
|45000001 |请求参数错误 |必填参数缺失（如 target_language 未指定） |
|45000130 |请求载荷过大 |text_list 列表长度超过 16 条，或单条文本 Token 数超过 1024 |
|55000001 |服务内部错误 |翻译服务内部处理异常，请重试。若多次重试失败，请联系客服。 |


<span id="H7x6qhh5"></span>
# 语言支持

本接口使用 ISO 639\-1 / BCP\-47 标准语言代码。当前支持以下 32 种语言互译：


|语言代码 |语言名称 |语言代码 |语言名称 |
|---|---|---|---|
|zh |中文（简体） |en |英语 |
|ja |日语 |ko |韩语 |
|fr |法语 |de |德语 |
|es |西班牙语 |pt |葡萄牙语 |
|ru |俄语 |ar |阿拉伯语 |
|it |意大利语 |nl |荷兰语 |
|pl |波兰语 |ro |罗马尼亚语 |
|sv |瑞典语 |da |丹麦语 |
|nb |挪威语 |fi |芬兰语 |
|hu |匈牙利语 |cs |捷克语 |
|hr |克罗地亚语 |el |希腊语 |
|he |希伯来语 |tr |土耳其语 |
|uk |乌克兰语 |th |泰语 |
|vi |越南语 |id |印度尼西亚语 |
|ms |马来语 |tl |菲律宾语 |
|hi |印地语 |zh\-Hant |中文（繁体） |


<span id="KMtyqouu"></span>
# 示例

<span id="jSI4eiuP"></span>
## 请求示例

<span id="hfPlqFAT"></span>
### cURL

```Bash
curl --request POST \
  --url https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate \
  --header 'Content-Type: application/json' \
  --header 'X-Api-App-Key: {你的AppId}' \
  --header 'X-Api-Access-Key: {你的AccessKey}' \
  --header 'X-Api-Resource-Id: volc.speech.mt' \
  --header 'X-Api-Request-Id: {请求ID}' \
  --data '{
    "source_language": "zh",
    "target_language": "en",
    "text_list": [
        "字节跳动致力于激发创造、丰富生活"
    ]
}'
```


<span id="u5Isnzsr"></span>
### Python

```Python
import json
import uuid
import requests


def translate(source_language=None, target_language="en", text_list=None, corpus=None):
    url = "https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate"

    # 新版控制台使用 X-Api-Key；旧版控制台使用 X-Api-App-Key + X-Api-Access-Key
    app_id = "{你的AppId}"
    access_key = "{你的AccessKey}"

    headers = {
        "X-Api-App-Key": app_id,
        "X-Api-Access-Key": access_key,
        "X-Api-Resource-Id": "volc.speech.mt",
        "X-Api-Request-Id": str(uuid.uuid4()),
        "Content-Type": "application/json",
    }

    body = {
        "target_language": target_language,
        "text_list": text_list or [],
    }
    if source_language:
        body["source_language"] = source_language
    if corpus:
        body["corpus"] = corpus

    response = requests.post(url, json=body, headers=headers)
    result = response.json()

    if result.get("code") == 20000000:
        print("翻译成功:")
        for item in result["data"]["translation_list"]:
            print(f"  译文: {item['translation']}")
            if item.get("detected_source_language"):
                print(f"  检测到源语言: {item['detected_source_language']}")
    else:
        print(f"翻译失败: code={result.get('code')}, message={result.get('message')}")

    return result


if __name__ == "__main__":
    # 示例 1：指定源语言翻译
    translate(
        source_language="zh",
        target_language="en",
        text_list=["字节跳动致力于激发创造、丰富生活", "火山引擎是字节跳动旗下的企业级智能技术服务平台"],
    )

    # 示例 2：自动检测源语言
    translate(
        target_language="zh",
        text_list=["ByteDance is building a global platform for knowledge and entertainment"],
    )

    # 示例 3：使用术语
    translate(
        source_language="en",
        target_language="zh",
        text_list=["Volcengine provides a comprehensive suite of cloud services and AI solutions"],
        corpus={
            "glossary_list": {
                "Volcengine": "火山引擎",
            }
        },
    )
```


<span id="ml7m9Swk"></span>
## 返回示例

<span id="GqhOzg7S"></span>
### 成功响应

```JSON
{
    "code": 20000000,
    "message": "ok",
    "data": {
        "translation_list": [
            {
                "translation": "ByteDance is committed to inspiring creativity and enriching lives",
                "usage": {
                    "prompt_tokens": 31,
                    "completion_tokens": 20,
                    "total_tokens": 51
                }
            }
        ]
    }
}
```


<span id="29MYQYpN"></span>
### 自动检测源语言

未指定 source_language 时，响应中会返回 detected_source_language 字段：

```JSON
{
    "code": 20000000,
    "message": "ok",
    "data": {
        "translation_list": [
            {
                "translation": "字节跳动正在打造一个全球性的知识与娱乐平台。",
                "detected_source_language": "en",
                "usage": {
                    "prompt_tokens": 30,
                    "completion_tokens": 20,
                    "total_tokens": 50
                }
            }
        ]
    }
}
```


<span id="A0PO0acJ"></span>
### 错误响应

目标语言未指定：

```JSON
{
    "code": 45000001,
    "message": "target_language is required"
}
```


文本列表超长：

```JSON
{
    "code": 45000130,
    "message": "overflow text_list(17>16)"
}
```


单条文本 Token 超限：

```JSON
{
    "code": 45000130,
    "message": "text_list[0] overflow tokens(1280>1024)"
}
```


服务内部错误：

```JSON
{
    "code": 55000001,
    "message": "pipeline failed: ..."
}
```


