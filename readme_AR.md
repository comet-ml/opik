> ملاحظة: تمت ترجمة هذا الملف آليًا. نرحب بتحسينات الترجمة!

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>


<h1 align="center" style="border-bottom: none">
<div>
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
<source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
<img alt="شعار Comet Opik" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
</picture></a>
<br>
أوبيك
</div>
</h1>
<h2 align="center" style="border-bottom: none">إمكانية مراقبة وتقييم وتحسين الذكاء الاصطناعي مفتوح المصدر</h2>
<p align="center">
تساعدك Opik على بناء واختبار وتحسين تطبيقات الذكاء الاصطناعي التوليدية التي تعمل بشكل أفضل، بدءًا من النموذج الأولي ووصولاً إلى الإنتاج.  من روبوتات الدردشة RAG إلى مساعدي التعليمات البرمجية إلى الأنظمة الوكيلة المعقدة، توفر Opik تتبعًا شاملاً وتقييمًا وتحسينًا تلقائيًا للأدوات والأدوات للتخلص من التخمين في تطوير الذكاء الاصطناعي.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![الترخيص](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
[![المكافآت](https://img.shields.io/endpoint?url=https%3A%2F%2Falgora.io%2Fapi%2Fshields%2Fcomet-ml%2Fbounties%3Fstatus%3Dopen)](https://algora.io/comet-ml/bounties?status=open)

<!-- [![البدء السريع](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>موقع الويب</b></a> •
<a href="https://chat.comet.com"><b>مجتمع Slack</b></a> •
<a href="https://x.com/Cometml"><b>Twitter</b></a> •
<a href="https://www.comet.com/docs/opik/changelog"><b>سجل التغيير</b></a> •
<a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>الوثائق</b></a>
</div>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 ما هو Opik؟</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ تثبيت خادم Opik</a> • <a href="#-opik-client-sdk">💻 Opik Client SDK</a> • <a href="#-logging-traces-with-integrations">📝 التسجيل آثار</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM كقاضي</a> • <a href="#-evaluating-your-llm-application">🔍 تقييم طلبك</a> • <a href="#-star-us-on-github">⭐ ميزنا</a> • <a href="#-contributing">🤝 المساهمة</a>
</div>

<br>

[![لقطة شاشة لمنصة Opik (thumbnail)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 ما هو أوبيك؟

Opik (تم إنشاؤه بواسطة [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)) عبارة عن منصة مفتوحة المصدر مصممة لتبسيط دورة الحياة الكاملة لتطبيقات LLM. إنه يمكّن المطورين من تقييم واختبار ومراقبة وتحسين نماذجهم وأنظمتهم الوكيلة. تشمل العروض الرئيسية ما يلي:

- ** إمكانية المراقبة الشاملة **: التتبع العميق لمكالمات LLM وتسجيل المحادثات ونشاط الوكيل.
- **التقييم المتقدم**: تقييم سريع قوي، ماجستير في القانون كقاض، وإدارة التجربة.
- **جاهز للإنتاج**: لوحات معلومات مراقبة قابلة للتطوير وقواعد تقييم عبر الإنترنت للإنتاج.
- **Opik Agent Optimizer**: مجموعة SDK مخصصة ومجموعة من أدوات التحسين لتحسين المطالبات والوكلاء.
- **Opik Guardrails**: ميزات تساعدك على تنفيذ ممارسات الذكاء الاصطناعي الآمنة والمسؤولة.

<br>

تشمل القدرات الرئيسية ما يلي:

- ** التطوير والتتبع: **
- تتبع جميع مكالمات وتتبعات LLM بسياق تفصيلي أثناء التطوير وفي الإنتاج ([Quickstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
- عمليات تكامل واسعة النطاق مع جهات خارجية لسهولة المراقبة: التكامل بسلاسة مع قائمة متزايدة من أطر العمل، ودعم العديد من أكبرها وأكثرها شيوعًا محليًا (بما في ذلك الإضافات الحديثة مثل **Google ADK**، **Autogen**، و **Flowise AI**). ([عمليات التكامل](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
- قم بتعليق الآثار والامتدادات بدرجات التعليقات عبر ملف [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) أو [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
- قم بتجربة المطالبات والنماذج في [ساحة اللعب السريعة](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **التقييم والاختبار**:
- أتمتة تقييم طلب LLM الخاص بك باستخدام [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) و [التجارب](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
- الاستفادة من مقاييس LLM-as-a-judge القوية للمهام المعقدة مثل [اكتشاف الهلوسة](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik)، [الإشراف](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)، وتقييم RAG ([الإجابة الصلة](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)، [السياق الدقة](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
- دمج التقييمات في مسار CI/CD الخاص بك من خلال [تكامل PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **مراقبة الإنتاج وتحسينه**:
- تسجيل كميات كبيرة من آثار الإنتاج: تم تصميم Opik على نطاق واسع (أكثر من 40 مليون أثر/يوم).
- مراقبة نتائج التعليقات وأعداد التتبع واستخدام الرموز المميزة بمرور الوقت في [Opik Dashboard](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
- استخدم [قواعد التقييم عبر الإنترنت](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) مع مقاييس LLM-as-a-Judge لتحديد مشكلات الإنتاج.
- الاستفادة من **Opik Agent Optimizer** و**Opik Guardrails** لتحسين وتأمين تطبيقات LLM الخاصة بك في الإنتاج بشكل مستمر.

> [!TIP]
> إذا كنت تبحث عن ميزات لا تتوفر في Opik اليوم، فيرجى رفع [طلب ميزة جديدة](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ تثبيت خادم Opik

احصل على خادم Opik الخاص بك قيد التشغيل خلال دقائق. اختر الخيار الذي يناسب احتياجاتك:

### الخيار 1: سحابة Comet.com (الأسهل والموصى بها)

قم بالوصول إلى Opik على الفور دون أي إعداد. مثالية للبدء السريع والصيانة الخالية من المتاعب.

👉 [إنشاء حساب Comet المجاني الخاص بك](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### الخيار 2: المضيف الذاتي Opik للتحكم الكامل

قم بنشر Opik في بيئتك الخاصة. اختر بين Docker للإعدادات المحلية أو Kubernetes لقابلية التوسع.

#### الاستضافة الذاتية مع Docker Compose (للتطوير المحلي والاختبار)

هذه هي أبسط طريقة لتشغيل مثيل Opik المحلي. لاحظ نص التثبيت الجديد `./opik.sh`:

في بيئة Linux أو Mac:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

في بيئة ويندوز:

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

** ملفات تعريف الخدمة للتطوير **

تدعم البرامج النصية لتثبيت Opik الآن ملفات تعريف الخدمة لسيناريوهات التطوير المختلفة:

```bash
# Start full Opik suite (default behavior)
./opik.sh

# Start only infrastructure services (databases, caches etc.)
./opik.sh --infra

# Start infrastructure + backend services
./opik.sh --backend

# Enable guardrails with any profile
./opik.sh --guardrails # Guardrails with full Opik suite
./opik.sh --backend --guardrails # Guardrails with infrastructure + backend
```

استخدم خيارات `--help` أو `--info` لاستكشاف المشكلات وإصلاحها. تضمن ملفات Dockerfiles الآن تشغيل الحاويات كمستخدمين غير جذريين لتعزيز الأمان. بمجرد الانتهاء من تشغيل كل شيء، يمكنك الآن زيارة [localhost:5173](http://localhost:5173) على متصفحك! للحصول على تعليمات تفصيلية، راجع [دليل النشر المحلي](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### الاستضافة الذاتية مع Kubernetes & Helm (عمليات النشر القابلة للتوسع)

بالنسبة للإنتاج أو عمليات النشر المستضافة ذاتيًا على نطاق واسع، يمكن تثبيت Opik على مجموعة Kubernetes باستخدام مخطط Helm الخاص بنا. انقر على الشارة للحصول على [دليل تثبيت Kubernetes باستخدام Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) الكامل.

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> ** تغييرات الإصدار 1.7.0 **: يرجى مراجعة [سجل التغيير](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) للحصول على التحديثات المهمة والتغييرات العاجلة.

<a id="-opik-client-sdk"></a>
## 💻 عميل Opik SDK

توفر Opik مجموعة من مكتبات العملاء وREST API للتفاعل مع خادم Opik. يتضمن ذلك حزم SDK لـ Python وTypeScript وRuby (عبر OpenTelemetry)، مما يسمح بالتكامل السلس في سير العمل لديك. للحصول على مراجع تفصيلية لواجهة برمجة التطبيقات (API) وSDK، راجع [الوثائق المرجعية لعميل Opik](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### البداية السريعة لـ Python SDK

للبدء باستخدام Python SDK:

تثبيت الحزمة:

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

قم بتكوين python SDK عن طريق تشغيل الأمر `opikconfig`، والذي سيطالبك بعنوان خادم Opik الخاص بك (للمثيلات المستضافة ذاتيًا) أو مفتاح API ومساحة العمل (لـ Comet.com):

```bash
opik configure
```

> [!TIP]
> يمكنك أيضًا الاتصال بـ `opik.configure(use_local=True)` من كود Python الخاص بك لتكوين SDK ليتم تشغيله على تثبيت محلي مستضاف ذاتيًا، أو توفير مفتاح واجهة برمجة التطبيقات وتفاصيل مساحة العمل مباشرةً لموقع Comet.com. ارجع إلى [وثائق Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) لمزيد من خيارات التكوين.

أنت الآن جاهز لبدء تسجيل التتبعات باستخدام [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

<a id="-logging-traces-with-integrations"></a>
### 📝 تسجيل الآثار بالتكاملات

أسهل طريقة لتسجيل التتبعات هي استخدام إحدى عمليات التكامل المباشرة لدينا. يدعم Opik مجموعة واسعة من أطر العمل، بما في ذلك الإضافات الحديثة مثل **Google ADK** و **Autogen** و **AG2** و **Flowise AI**:

| التكامل | الوصف | التوثيق |
| --------------------- | ------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| أدك | تسجيل التتبعات لـ Google Agent Development Kit (ADK) | [التوثيق](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) |
| AG2 | تسجيل التتبعات لمكالمات AG2 LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik) |
| جناح | تسجيل التتبعات لمكالمات aisuite LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik) |
| أجنو | تسجيل التتبعات لاستدعاءات إطار عمل تنسيق وكيل Agno | [التوثيق](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik) |
| انثروبي | تسجيل التتبعات لمكالمات Anthropic LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik) |
| أوتوجين | تسجيل التتبعات لسير عمل وكيل Autogen | [التوثيق](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik) |
| حجر الأساس | تسجيل التتبعات لمكالمات Amazon Bedrock LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik) |
| بي آي (بايثون) | تتبعات السجل لاستدعاءات إطار عمل وكيل BeeAI Python | [التوثيق](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik) |
| BeeAI (تايب سكريبت) | تسجيل تتبعات لاستدعاءات إطار عمل وكيل BeeAI TypeScript | [التوثيق](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik) |
| بايت بلس | تسجيل التتبعات لمكالمات BytePlus LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik) |
| عمال Cloudflare الذكاء الاصطناعي | تسجيل التتبعات لمكالمات Cloudflare Workers AI | [التوثيق](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| كوهير | تسجيل التتبعات لمكالمات Cohere LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik) |
| كرو آي | تسجيل التتبعات لمكالمات CrewAI | [التوثيق](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik) |
| المؤشر | تسجيل آثار محادثات المؤشر | [التوثيق](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik) |
| ديب سيك | تسجيل تتبعات لمكالمات DeepSeek LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik) |
| ديفي | تسجيل التتبعات لتشغيل وكيل Dify | [التوثيق](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik) |
| دسبي | تسجيل التتبعات لتشغيل DSPy | [التوثيق](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik) |
| الألعاب النارية منظمة العفو الدولية | تسجيل آثار مكالمات Fireworks AI LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik) |
| فلويز الذكاء الاصطناعي | تسجيل التتبعات لمنشئ Flowise AI visual LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik) |
| الجوزاء (بيثون) | تسجيل التتبعات لمكالمات Google Gemini LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik) |
| الجوزاء (تايب سكريبت) | سجل تتبعات مكالمات Google Gemini TypeScript SDK | [التوثيق](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik) |
| جروك | تسجيل تتبعات لمكالمات Groq LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik) |
| الدرابزين | تسجيل التتبعات لعمليات التحقق من صحة Guardrails AI | [التوثيق](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik) |
| كومة قش | سجل آثار مكالمات Haystack | [التوثيق](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik) |
| الميناء | تتبعات السجل لتجارب تقييم معيار Harbour | [التوثيق](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik) |
| مدرس | سجل تتبعات مكالمات LLM التي تم إجراؤها باستخدام Instructor | [التوثيق](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik) |
| لانج تشين (بيثون) | تسجيل التتبعات لمكالمات LangChain LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) |
| لانج تشين (JS/TS) | تسجيل التتبعات لمكالمات LangChain JavaScript/TypeScript | [التوثيق](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik) |
| لانغغراف | تتبعات السجل لعمليات تنفيذ LangGraph | [التوثيق](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik) |
| لانجفلو | تسجيل تتبعات لـ Langflow visual AI builder | [التوثيق](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik) |
| لايت إل إل إم | تسجيل التتبعات لاستدعاءات نموذج LiteLLM | [التوثيق](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik) |
| وكلاء LiveKit | تسجيل التتبعات لاستدعاءات إطار عمل وكيل LiveKit Agents AI | [التوثيق](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik) |
| مؤشر اللاما | تسجيل تتبعات لمكالمات LlamaIndex LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) |
| ماسترا | تسجيل التتبعات لاستدعاءات إطار عمل سير عمل Mastra AI | [التوثيق](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik) |
| Microsoft Agent Framework (بيثون) | تسجيل التتبعات لمكالمات Microsoft Agent Framework | [التوثيق](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik) |
| Microsoft Agent Framework (.NET) | تسجيل التتبعات لمكالمات Microsoft Agent Framework .NET | [التوثيق](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| ميسترال لمنظمة العفو الدولية | تسجيل التتبعات لمكالمات Mistral AI LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik) |
| ن8ن | تسجيل التتبعات لعمليات تنفيذ سير العمل n8n | [التوثيق](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik) |
| نوفيتا منظمة العفو الدولية | تسجيل التتبعات لمكالمات Novita AI LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik) |
| أولاما | تسجيل تتبعات لمكالمات Ollama LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik) |
| أوبن إيه آي (بيثون) | تسجيل التتبعات لمكالمات OpenAI LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) |
| OpenAI (JS/TS) | تسجيل التتبعات لاستدعاءات OpenAI JavaScript/TypeScript | [التوثيق](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik) |
| وكلاء OpenAI | تسجيل التتبعات لمكالمات OpenAI Agents SDK | [التوثيق](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik) |
| اوبن راوتر | تسجيل التتبعات لمكالمات OpenRouter LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik) |
| القياس عن بعد مفتوح | تسجيل التتبعات للمكالمات المدعومة من OpenTelemetry | [التوثيق](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik) |
| واجهة ويب مفتوحة | تسجيل التتبعات لمحادثات OpenWebUI | [التوثيق](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik) |
| بيبيكات | سجل آثار مكالمات وكيل الصوت في الوقت الحقيقي لـ Pipecat | [التوثيق](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik) |
| بريديباس | تسجيل التتبعات لمكالمات Predibase LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik) |
| الذكاء الاصطناعي Pydantic | تسجيل تتبعات مكالمات وكيل PydanticAI | [التوثيق](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) |
| راجاس | تتبعات السجل لتقييمات Ragas | [التوثيق](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik) |
| النواة الدلالية | تسجيل التتبعات لاستدعاءات Microsoft Semantic Kernel | [التوثيق](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik) |
| سمولوجينتس | سجل آثار وكلاء Smolagents | [التوثيق](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik) |
| الربيع لمنظمة العفو الدولية | تتبعات السجل لاستدعاءات إطار عمل Spring AI | [التوثيق](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik) |
| وكلاء ستراندس | تسجيل التتبعات لمكالمات وكلاء Strands | [التوثيق](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik) |
| معا منظمة العفو الدولية | تسجيل التتبعات لمكالمات Together AI LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik) |
| فيرسيل AI SDK | تسجيل التتبعات لمكالمات Vercel AI SDK | [التوثيق](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik) |
| فولتاجنت | تسجيل التتبعات لاستدعاءات إطار عمل وكيل VoltAgent | [التوثيق](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik) |
| واتسون اكس | تسجيل التتبعات لمكالمات IBM watsonx LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik) |
| xAI جروك | تسجيل تتبعات لمكالمات xAI Grok LLM | [التوثيق](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik) |

> [!TIP]
> إذا لم يكن إطار العمل الذي تستخدمه مدرجًا أعلاه، فلا تتردد في [فتح مشكلة](https://github.com/comet-ml/opik/issues) أو إرسال علاقة عامة مع التكامل.

إذا كنت لا تستخدم أيًا من أطر العمل المذكورة أعلاه، فيمكنك أيضًا استخدام أداة تزيين الوظائف `track` لـ [تسجيل التتبعات](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik):

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> يمكن استخدام مصمم المسار جنبًا إلى جنب مع أي من عمليات التكامل لدينا ويمكن استخدامه أيضًا لتتبع استدعاءات الوظائف المتداخلة.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ LLM كمقاييس القاضي

يتضمن Python Opik SDK عددًا من LLM كمقاييس تحكيمية لمساعدتك في تقييم تطبيق LLM الخاص بك. تعرف على المزيد حول هذا الموضوع في [وثائق المقاييس](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

لاستخدامها، ما عليك سوى استيراد المقياس ذي الصلة واستخدام وظيفة "النتيجة":

```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="What is the capital of France?",
    output="Paris",
    context=["France is a country in Europe."]
)
print(score)
```

يتضمن Opik أيضًا عددًا من المقاييس الإرشادية المعدة مسبقًا بالإضافة إلى القدرة على إنشاء المقاييس الخاصة بك. تعرف على المزيد حول ذلك في [وثائق المقاييس](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

<a id="-evaluating-your-llm-application"></a>
### 🔍 تقييم طلبات الحصول على ماجستير إدارة الأعمال

يتيح لك Opik تقييم تطبيق LLM الخاص بك أثناء التطوير من خلال [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) و [التجارب](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). توفر لوحة معلومات Opik مخططات محسنة للتجارب ومعالجة أفضل للآثار الكبيرة. يمكنك أيضًا إجراء التقييمات كجزء من مسار CI/CD الخاص بك باستخدام [تكامل PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

<a id="-star-us-on-github"></a>
## ⭐ قم بتمييزنا على GitHub

إذا وجدت Opik مفيدًا، فيرجى التفكير في منحنا نجمة! يساعدنا دعمك على تنمية مجتمعنا ومواصلة تحسين المنتج.

[![مخطط تاريخ النجوم](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 المساهمة

هناك طرق عديدة للمساهمة في Opik:

- إرسال [تقارير الأخطاء](https://github.com/comet-ml/opik/issues) و[طلبات الميزات](https://github.com/comet-ml/opik/issues)
- راجع الوثائق وأرسل [طلبات السحب](https://github.com/comet-ml/opik/pulls) لتحسينها
- التحدث أو الكتابة عن Opik و[إعلامنا](https://chat.comet.com)
- التصويت لصالح [طلبات الميزات الشائعة](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) لإظهار دعمك

لمعرفة المزيد حول كيفية المساهمة في Opik، يرجى الاطلاع على [إرشادات المساهمة](CONTRIBUTING.md).
