---
name: generate-ppt
description: Generate a downloadable PowerPoint presentation from the entire current conversation, including query tables, charts and explanations.
type: executable
category: analysis
title: Generate PPT
tags: [ppt, powerpoint, presentation]
useCases: [Create a PPT from the conversation]
timeout: 600000
outputType: json
version: 1.0.0
defaultEnabled: false
entries:
  - scripts/index.mjs
---
# Generate PPT

Call run_skill with skill_id `generate-ppt`. No question_id or generation parameters are needed.
The host securely collects the entire current conversation, its query results and charts.
Do not ask the user to repeat the conversation. Do not pass a model-written conversation or override the title, style, cover or page count.
Return the resulting Markdown download link to the user. Include any warnings returned by the service.
Never claim that a PPT is generated unless the tool returns a real file URL.
