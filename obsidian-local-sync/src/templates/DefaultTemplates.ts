/**
 * 内置默认模板
 *
 * 用户可在设置面板中覆盖这些模板。
 * 模板变量:
 *   {{title}} - 素材标题
 *   {{source}} - 来源域名
 *   {{url}} - 原始链接
 *   {{date}} - 创建日期
 *   {{body}} - 正文内容
 *   {{tags}} - AI生成的标签列表
 *   {{summary}} - AI生成的摘要
 */

/** 链接类 (公众号/网页) 的默认输出模板 */
export const LINK_TEMPLATE = `---
title: "{{title}}"
source: "{{source}}"
url: "{{url}}"
date: "{{date}}"
tags: [{{tags}}]
---

# {{title}}

> 来源: [{{source}}]({{url}})

{{summary}}

---

{{body}}
`;

/** 视频类 的默认输出模板 */
export const VIDEO_TEMPLATE = `---
title: "{{title}}"
source: "{{source}}"
url: "{{url}}"
date: "{{date}}"
tags: [{{tags}}]
type: "video"
---

# {{title}}

📺 **视频链接:** {{url}}

{{summary}}

---

{{body}}
`;

/** 图片类 的默认输出模板 */
export const IMAGE_TEMPLATE = `---
title: "{{title}}"
date: "{{date}}"
tags: [{{tags}}]
type: "image"
---

# {{title}}

{{body}}

{{summary}}
`;

/** 纯文字类 的默认输出模板 */
export const PLAINTEXT_TEMPLATE = `---
title: "{{title}}"
date: "{{date}}"
tags: [{{tags}}]
---

{{body}}
`;
