> नोट: यह फ़ाइल मशीन द्वारा अनुवादित की गई है। अनुवाद में सुधार के लिए आपका स्वागत है!

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>


<h1 align="center" style="border-bottom: none">
<div>
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
<source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
<img alt="धूमकेतु ओपिक लोगो" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
</picture></a>
<br>
ओपिक
</div>
</h1>
<h2 align="center" style="border-bottom: none">ओपन-सोर्स एआई अवलोकन, मूल्यांकन और अनुकूलन</h2>
<p align="center">
ओपिक आपको जेनरेटिव एआई एप्लिकेशन बनाने, परीक्षण करने और अनुकूलित करने में मदद करता है जो प्रोटोटाइप से उत्पादन तक बेहतर ढंग से चलता है।  आरएजी चैटबॉट्स से लेकर कोड असिस्टेंट से लेकर जटिल एजेंटिक सिस्टम तक, ओपिक एआई विकास से अनुमान लगाने के लिए व्यापक ट्रेसिंग, मूल्यांकन और स्वचालित प्रॉम्प्ट और टूल अनुकूलन प्रदान करता है।
</p>

<div align="center">

[![पायथन एसडीके](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![लाइसेंस](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![बिल्ड](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
[![इनाम](https://img.shields.io/endpoint?url=https%3A%2F%2Falgora.io%2Fapi%2Fshields%2Fcomet-ml%2Fbounties%3Fstatus%3Dopen)](https://algora.io/comet-ml/bounties?status=open)

<!-- [![त्वरित प्रारंभ](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
<a href='https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik'><b>वेबसाइट</b></a> •
<a href='https://chat.comet.com'><b>स्लैक कम्युनिटी</b></a> •
<a href='https://x.com/Cometml'><b>ट्विटर</b></a> •
<a href='https://www.comet.com/docs/opik/changelog'><b>Changelog</b></a> •
<a href='https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik'><b>दस्तावेज़ीकरण</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href='#-what-is-opik'>🚀 Opik क्या है?</a> • <a href='#%EF%B8%8F-opik-server-installation'>🛠️ Opik सर्वर इंस्टालेशन</a> • <a href='#-opik-client-sdk'>💻 Opik क्लाइंट SDK</a> • <a href='#-लॉगिंग-ट्रेस-विथ-इंटीग्रेशन'>📝 लॉगिंग ट्रेस</a><br>
<a href='#-llm-as-a-judge-metrics'>🧑‍⚖️ जज के रूप में LLM</a> • <a href='#-evaluating-your-llm-application'>🔍 आपके आवेदन का मूल्यांकन</a> • <a href='#-star-us-on-github'>⭐ स्टार अस</a> • <a href='#-contributing'>🤝 योगदान</a>
</div>

<br>

[![ओपिक प्लेटफ़ॉर्म स्क्रीनशॉट (थंबनेल)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀ओपिक क्या है?

ओपिक ([Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik) द्वारा निर्मित) एक ओपन-सोर्स प्लेटफ़ॉर्म है जिसे एलएलएम अनुप्रयोगों के संपूर्ण जीवनचक्र को सुव्यवस्थित करने के लिए डिज़ाइन किया गया है। यह डेवलपर्स को अपने मॉडल और एजेंटिक सिस्टम का मूल्यांकन, परीक्षण, निगरानी और अनुकूलन करने का अधिकार देता है। प्रमुख पेशकशों में शामिल हैं:

- **व्यापक अवलोकन**: एलएलएम कॉल, वार्तालाप लॉगिंग और एजेंट गतिविधि का गहन पता लगाना।
- **उन्नत मूल्यांकन**: मजबूत त्वरित मूल्यांकन, एलएलएम-ए-जज, और प्रयोग प्रबंधन।
- **उत्पादन के लिए तैयार**: उत्पादन के लिए स्केलेबल निगरानी डैशबोर्ड और ऑनलाइन मूल्यांकन नियम।
- **ओपिक एजेंट ऑप्टिमाइज़र**: संकेतों और एजेंटों को बढ़ाने के लिए समर्पित एसडीके और ऑप्टिमाइज़र का सेट।
- **ओपिक रेलिंग**: सुरक्षित और जिम्मेदार एआई प्रथाओं को लागू करने में आपकी मदद करने वाली सुविधाएँ।

<br>

प्रमुख क्षमताओं में शामिल हैं:

- **विकास एवं अनुरेखण:**
- विकास और उत्पादन के दौरान विस्तृत संदर्भ के साथ सभी एलएलएम कॉल और ट्रेस को ट्रैक करें ([क्विकस्टार्ट](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik))।
- आसान अवलोकन के लिए व्यापक तृतीय-पक्ष एकीकरण: फ्रेमवर्क की बढ़ती सूची के साथ निर्बाध रूप से एकीकृत, कई सबसे बड़े और सबसे लोकप्रिय लोगों को मूल रूप से समर्थन (**Google ADK**, **ऑटोजेन**, और **फ्लोवाइज एआई** जैसे हालिया परिवर्धन सहित)। ([एकीकरण](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
- [पायथन के माध्यम से फीडबैक स्कोर के साथ निशान और विस्तार को एनोटेट करें SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) या [यूआई](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik)।
- [प्रॉम्प्ट प्लेग्राउंड](https://www.comet.com/docs/opik/prompt_engineering/playground) में संकेतों और मॉडलों के साथ प्रयोग करें।

- **मूल्यांकन एवं परीक्षण**:
- [डेटासेट](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) के साथ अपने एलएलएम एप्लिकेशन मूल्यांकन को स्वचालित करें और [प्रयोग](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)।
- [मतिभ्रम का पता लगाने](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik) जैसे जटिल कार्यों के लिए शक्तिशाली एलएलएम-ए-जज मेट्रिक्स का लाभ उठाएं। [संयम](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik), और RAG मूल्यांकन ([उत्तर प्रासंगिकता](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [संदर्भ परिशुद्धता](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik))।
- हमारे [PyTest एकीकरण](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik) के साथ अपने CI/CD पाइपलाइन में मूल्यांकन को एकीकृत करें।

- **उत्पादन निगरानी एवं अनुकूलन**:
- उत्पादन निशानों की उच्च मात्रा लॉग करें: ओपिक को स्केल (40M+ निशान/दिन) के लिए डिज़ाइन किया गया है।
- [ओपिक डैशबोर्ड](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) में समय के साथ फीडबैक स्कोर, ट्रेस काउंट और टोकन उपयोग की निगरानी करें।
- उत्पादन समस्याओं की पहचान करने के लिए एलएलएम-ए-जज मेट्रिक्स के साथ [ऑनलाइन मूल्यांकन नियम](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) का उपयोग करें।
- उत्पादन में अपने एलएलएम अनुप्रयोगों को लगातार बेहतर बनाने और सुरक्षित करने के लिए **ओपिक एजेंट ऑप्टिमाइज़र** और **ओपिक रेलिंग** का लाभ उठाएं।

> [!TIP]
> यदि आप उन सुविधाओं की तलाश कर रहे हैं जो ओपिक के पास आज नहीं हैं, तो कृपया एक नया [सुविधा अनुरोध](https://github.com/comet-ml/opik/issues/new/choose) करें 🚀

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ ओपिक सर्वर इंस्टालेशन

अपने ओपिक सर्वर को मिनटों में चालू करें। वह विकल्प चुनें जो आपकी आवश्यकताओं के लिए सबसे उपयुक्त हो:

### विकल्प 1: Comet.com क्लाउड (सबसे आसान और अनुशंसित)

बिना किसी सेटअप के तुरंत ओपिक तक पहुंचें। त्वरित शुरुआत और परेशानी मुक्त रखरखाव के लिए आदर्श।

👉 [अपना निःशुल्क धूमकेतु खाता बनाएं](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### विकल्प 2: पूर्ण नियंत्रण के लिए सेल्फ-होस्ट ओपिक

ओपिक को अपने परिवेश में तैनात करें। स्थानीय सेटअप के लिए डॉकर या स्केलेबिलिटी के लिए कुबेरनेट्स में से चुनें।

#### डॉकर कंपोज़ के साथ स्व-होस्टिंग (स्थानीय विकास और परीक्षण के लिए)

स्थानीय ओपिक इंस्टेंस को चलाने का यह सबसे सरल तरीका है। नई `./opik.sh` इंस्टॉलेशन स्क्रिप्ट पर ध्यान दें:

Linux या Mac वातावरण पर:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

विंडोज़ परिवेश पर:

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**विकास के लिए सेवा प्रोफ़ाइल**

ओपिक इंस्टॉलेशन स्क्रिप्ट अब विभिन्न विकास परिदृश्यों के लिए सेवा प्रोफाइल का समर्थन करती है:

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

समस्याओं के निवारण के लिए `--help` या `--info` विकल्पों का उपयोग करें। Dockerfiles अब बेहतर सुरक्षा के लिए कंटेनरों को गैर-रूट उपयोगकर्ताओं के रूप में चलाना सुनिश्चित करती है। एक बार सब ठीक हो जाए और चलने लगे, तो अब आप अपने ब्राउज़र पर [localhost:5173](http://localhost:5173) पर जा सकते हैं! विस्तृत निर्देशों के लिए, [स्थानीय परिनियोजन मार्गदर्शिका](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik) देखें।

#### कुबेरनेट्स और हेल्म के साथ स्व-होस्टिंग (स्केलेबल तैनाती के लिए)

उत्पादन या बड़े पैमाने पर स्व-होस्ट की गई तैनाती के लिए, ओपिक को हमारे हेल्म चार्ट का उपयोग करके कुबेरनेट्स क्लस्टर पर स्थापित किया जा सकता है। संपूर्ण [हेल्म का उपयोग करके कुबेरनेट्स इंस्टालेशन गाइड](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) के लिए बैज पर क्लिक करें।

[![कुबेरनेट्स](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **संस्करण 1.7.0 परिवर्तन**: कृपया महत्वपूर्ण अपडेट और महत्वपूर्ण परिवर्तनों के लिए [चेंजलॉग](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) देखें।

<a id="-opik-client-sdk"></a>
## 💻 ओपिक क्लाइंट एसडीके

Opik, Opik सर्वर के साथ इंटरैक्ट करने के लिए क्लाइंट लाइब्रेरीज़ का एक सूट और एक REST API प्रदान करता है। इसमें पायथन, टाइपस्क्रिप्ट और रूबी (ओपनटेलीमेट्री के माध्यम से) के लिए एसडीके शामिल हैं, जो आपके वर्कफ़्लो में निर्बाध एकीकरण की अनुमति देते हैं। विस्तृत एपीआई और एसडीके संदर्भों के लिए, [ओपिक क्लाइंट रेफरेंस डॉक्यूमेंटेशन](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik) देखें।

### पायथन एसडीके त्वरित प्रारंभ

पायथन एसडीके के साथ आरंभ करने के लिए:

पैकेज स्थापित करें:

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

`ओपिक कॉन्फिगर` कमांड चलाकर पायथन एसडीके को कॉन्फ़िगर करें, जो आपको आपके ओपिक सर्वर पते (स्वयं-होस्ट किए गए उदाहरणों के लिए) या आपकी एपीआई कुंजी और कार्यक्षेत्र (Comet.com के लिए) के लिए संकेत देगा:

```bash
opik configure
```

> [!TIP]
> आप एसडीके को स्थानीय स्व-होस्ट किए गए इंस्टॉलेशन पर चलाने के लिए कॉन्फ़िगर करने के लिए अपने पायथन कोड से `opik.configure(use_local=True)` पर भी कॉल कर सकते हैं, या Comet.com के लिए सीधे एपीआई कुंजी और कार्यक्षेत्र विवरण प्रदान कर सकते हैं। अधिक कॉन्फ़िगरेशन विकल्पों के लिए [पायथन एसडीके दस्तावेज़](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) देखें।

अब आप [पायथन एसडीके](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) का उपयोग करके ट्रेस लॉगिंग शुरू करने के लिए तैयार हैं।

<a id="-लॉगिंग-ट्रेस-विथ-इंटीग्रेशन"></a>
### 📝 एकीकरण के साथ लॉगिंग ट्रेस

निशानों को लॉग करने का सबसे आसान तरीका हमारे प्रत्यक्ष एकीकरणों में से किसी एक का उपयोग करना है। ओपिक फ्रेमवर्क की एक विस्तृत श्रृंखला का समर्थन करता है, जिसमें **Google ADK**, **ऑटोजेन**, **AG2**, और **फ्लोवाइज AI** जैसे हालिया परिवर्धन शामिल हैं:

| एकीकरण | विवरण | दस्तावेज़ीकरण |
| ---------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| एडीके | Google एजेंट डेवलपमेंट किट (ADK) के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) |
| एजी2 | AG2 LLM कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik) |
| AIsuite | ऐसुइट एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik) |
| अगनो | एग्नो एजेंट ऑर्केस्ट्रेशन फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik) |
| मानवशास्त्रीय | एंथ्रोपिक एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik) |
| ऑटोजेन | ऑटोजेन एजेंटिक वर्कफ़्लोज़ के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik) |
| आधारशिला | अमेज़ॅन बेडरॉक एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik) |
| BeeAI (पायथन) | बीएआई पायथन एजेंट फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik) |
| BeeAI (टाइपस्क्रिप्ट) | बीएआई टाइपस्क्रिप्ट एजेंट फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik) |
| बाइटप्लस | बाइटप्लस एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik) |
| क्लाउडफ्लेयर वर्कर्स एआई | क्लाउडफ्लेयर वर्कर्स एआई कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| सहभागी | कोहेयर एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik) |
| क्रूएआई | CrewAI कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik) |
| कर्सर | कर्सर वार्तालापों के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik) |
| डीपसीक | डीपसीक एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik) |
| दिफाई | Dify एजेंट रन के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik) |
| डीएसपीवाई | DSPy रन के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik) |
| आतिशबाजी एआई | फायरवर्क्स एआई एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik) |
| फ़्लोवाइज एआई | फ़्लोवाइज एआई विज़ुअल एलएलएम बिल्डर के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik) |
| मिथुन (पायथन) | Google जेमिनी एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik) |
| मिथुन (टाइपस्क्रिप्ट) | Google जेमिनी टाइपस्क्रिप्ट SDK कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik) |
| ग्रोक | ग्रोक एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik) |
| रेलिंग | रेलिंग एआई सत्यापन के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik) |
| हेस्टैक | हेस्टैक कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik) |
| हार्बर | हार्बर बेंचमार्क मूल्यांकन परीक्षणों के लिए लॉग निशान | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik) |
| प्रशिक्षक | प्रशिक्षक के साथ की गई एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik) |
| लैंगचेन (पायथन) | लैंगचेन एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) |
| लैंगचेन (जेएस/टीएस) | लैंगचेन जावास्क्रिप्ट/टाइपस्क्रिप्ट कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik) |
| लैंगग्राफ | लैंगग्राफ निष्पादन के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik) |
| लैंगफ्लो | लैंगफ़्लो विज़ुअल एआई बिल्डर के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik) |
| लाइटएलएलएम | लाइटएलएलएम मॉडल कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik) |
| लाइवकिट एजेंट | लाइवकिट एजेंट्स एआई एजेंट फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik) |
| लामाइंडेक्स | LlamaIndex LLM कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) |
| मस्तरा | मास्ट्रा एआई वर्कफ़्लो फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik) |
| माइक्रोसॉफ्ट एजेंट फ्रेमवर्क (पायथन) | माइक्रोसॉफ्ट एजेंट फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik) |
| माइक्रोसॉफ्ट एजेंट फ्रेमवर्क (.NET) | Microsoft एजेंट फ़्रेमवर्क .NET कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| मिस्ट्रल एआई | मिस्ट्रल एआई एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik) |
| n8n | n8n वर्कफ़्लो निष्पादन के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik) |
| नोविता एआई | नोविता एआई एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik) |
| ओलामा | ओलामा एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik) |
| ओपनएआई (पायथन) | OpenAI LLM कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) |
| ओपनएआई (जेएस/टीएस) | OpenAI जावास्क्रिप्ट/टाइपस्क्रिप्ट कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik) |
| ओपनएआई एजेंट | OpenAI एजेंट SDK कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik) |
| ओपनराउटर | ओपनराउटर एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik) |
| ओपनटेलीमेट्री | ओपनटेलीमेट्री समर्थित कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik) |
| ओपनवेबयूआई | OpenWebUI वार्तालापों के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik) |
| पाइपकैट | पिपेकैट रीयल-टाइम वॉयस एजेंट कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik) |
| प्रीडिबेस | प्रीडिबेस एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik) |
| पाइडेंटिक एआई | PydanticAI एजेंट कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) |
| राग | रागों के मूल्यांकन के लिए लॉग निशान | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik) |
| सिमेंटिक कर्नेल | माइक्रोसॉफ्ट सिमेंटिक कर्नेल कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik) |
| स्मोलएजेंट्स | स्मोलएजेंट एजेंटों के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik) |
| स्प्रिंग एआई | स्प्रिंग एआई फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik) |
| स्ट्रैंड्स एजेंट | स्ट्रैंड्स एजेंटों की कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik) |
| एक साथ एआई | टुगेदर एआई एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik) |
| वर्सेल एआई एसडीके | वर्सेल एआई एसडीके कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik) |
| वोल्टएजेंट | VoltAgent एजेंट फ्रेमवर्क कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik) |
| वॉटसनएक्स | आईबीएम वाटसनएक्स एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik) |
| xAI ग्रोक | xAI ग्रोक एलएलएम कॉल के लिए लॉग ट्रेस | [दस्तावेज़ीकरण](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik) |

> [!TIP]
> यदि आप जिस ढांचे का उपयोग कर रहे हैं वह ऊपर सूचीबद्ध नहीं है, तो बेझिझक [एक मुद्दा खोलें](https://github.com/comet-ml/opik/issues) या एकीकरण के साथ एक पीआर सबमिट करें।

यदि आप उपरोक्त किसी भी फ्रेमवर्क का उपयोग नहीं कर रहे हैं, तो आप 'ट्रैक' फ़ंक्शन डेकोरेटर का उपयोग [लॉग ट्रेस](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik) पर भी कर सकते हैं:

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> ट्रैक डेकोरेटर का उपयोग हमारे किसी भी एकीकरण के साथ किया जा सकता है और इसका उपयोग नेस्टेड फ़ंक्शन कॉल को ट्रैक करने के लिए भी किया जा सकता है।

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ जज मेट्रिक्स के रूप में एलएलएम

पायथन ओपिक एसडीके में आपके एलएलएम एप्लिकेशन का मूल्यांकन करने में मदद करने के लिए जज मेट्रिक्स के रूप में कई एलएलएम शामिल हैं। इसके बारे में [मेट्रिक्स दस्तावेज़](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik) में और जानें।

उनका उपयोग करने के लिए, बस प्रासंगिक मीट्रिक आयात करें और `स्कोर' फ़ंक्शन का उपयोग करें:

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

ओपिक में कई पूर्व-निर्मित अनुमानी मेट्रिक्स के साथ-साथ अपना स्वयं का निर्माण करने की क्षमता भी शामिल है। [मेट्रिक्स दस्तावेज़ीकरण](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik) में इसके बारे में और जानें।

<a id="-evaluating-your-llm-application"></a>
### 🔍 आपके एलएलएम अनुप्रयोगों का मूल्यांकन

ओपिक आपको [डेटासेट](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) के माध्यम से विकास के दौरान अपने एलएलएम एप्लिकेशन का मूल्यांकन करने की अनुमति देता है। [प्रयोग](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)। ओपिक डैशबोर्ड प्रयोगों और बड़े निशानों के बेहतर प्रबंधन के लिए उन्नत चार्ट प्रदान करता है। आप हमारे [PyTest एकीकरण](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik) का उपयोग करके अपने CI/CD पाइपलाइन के हिस्से के रूप में भी मूल्यांकन चला सकते हैं।

<a id="-star-us-on-github"></a>
## ⭐ हमें GitHub पर स्टार करें

यदि आपको ओपिक उपयोगी लगता है, तो कृपया हमें एक स्टार देने पर विचार करें! आपका समर्थन हमें अपने समुदाय को बढ़ाने और उत्पाद में सुधार जारी रखने में मदद करता है।

[![स्टार हिस्ट्री चार्ट](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 योगदान दे रहे हैं

ओपिक में योगदान करने के कई तरीके हैं:

- [बग रिपोर्ट](https://github.com/comet-ml/opik/issues) और [सुविधा अनुरोध](https://github.com/comet-ml/opik/issues) सबमिट करें
- दस्तावेज़ की समीक्षा करें और इसे सुधारने के लिए [पुल अनुरोध](https://github.com/comet-ml/opik/pulls) सबमिट करें
- ओपिक के बारे में बोलना या लिखना और [हमें बताना](https://chat.comet.com)
- अपना समर्थन दिखाने के लिए [लोकप्रिय सुविधा अनुरोध](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) को अपवोट करना

ओपिक में योगदान करने के तरीके के बारे में अधिक जानने के लिए, कृपया हमारे [योगदान दिशानिर्देश](CONTRIBUTING.md) देखें।
