> Not: Bu dosya makine tarafından çevrilmiştir. Çeviri iyileştirmelerine katkılarınız memnuniyetle karşılanır!

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a><br><a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>


<h1 align="center" style="border-bottom: none">
<div>
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
<source media = "(prefers-color-scheme: koyu)" srcset = "https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
<source media = "(prefers-color-scheme: light)" srcset = "https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
<img alt = "Comet Opik logosu" src = "https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width = "200" />
</resim></a>
<br>
Opik
</div>
</h1>
<h2 align="center" style="border-bottom: none">Açık Kaynak Yapay Zeka Gözlemlenebilirliği, Değerlendirmesi ve Optimizasyonu</h2>
<p align="center">
Opik, prototipten üretime kadar daha iyi çalışan üretken yapay zeka uygulamaları oluşturmanıza, test etmenize ve optimize etmenize yardımcı olur.  RAG sohbet robotlarından kod asistanlarına ve karmaşık aracı sistemlere kadar Opik, yapay zeka geliştirmede tahmine dayalı çalışmayı ortadan kaldırmak için kapsamlı izleme, değerlendirme ve otomatik bilgi istemi ve araç optimizasyonu sağlar.
</p>

<div align="center">

[![Python SDK'sı](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![Lisans](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Derleme](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)

<!-- [![Hızlı Başlangıç](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Web sitesi</b></a> •
<a href="https://chat.comet.com"><b>Slack Topluluğu</b></a> •
<a href="https://x.com/Cometml"><b>Twitter</b></a> •
<a href="https://www.comet.com/docs/opik/changelog"><b>Değişiklik günlüğü</b></a> •
<a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Belgeler</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 Opik nedir?</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Opik Sunucu Kurulumu</a> • <a href="#-opik-client-sdk">💻 Opik İstemci SDK'sı</a> • <a href="#-logging-traces-with-integrations">📝 İzleri Günlüğe Kaydetmek</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ Hakim Olarak Yüksek Lisans</a> • <a href="#-evaluating-your-llm-application">🔍 Başvurunuzu Değerlendirme</a> • <a href="#-star-us-on-github">⭐ Bize Yıldız Verin</a> • <a href="#-contributing">🤝 Katkıda Bulunma</a>
</div>

<br>

[![Opik platformu ekran görüntüsü (küçük resim)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 Opik Nedir?

Opik ([Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)) tarafından oluşturulmuştur, LLM uygulamalarının tüm yaşam döngüsünü kolaylaştırmak için tasarlanmış açık kaynaklı bir platformdur. Geliştiricilere modellerini ve aracı sistemlerini değerlendirme, test etme, izleme ve optimize etme yetkisi verir. Anahtar teklifler şunları içerir:

- **Kapsamlı Gözlemlenebilirlik**: LLM çağrılarının, konuşma günlüğünün ve temsilci etkinliğinin derinlemesine izlenmesi.
- **Gelişmiş Değerlendirme**: Güçlü anında değerlendirme, yargıç olarak Yüksek Lisans ve deney yönetimi.
- **Üretime Hazır**: Ölçeklenebilir izleme kontrol panelleri ve üretim için çevrimiçi değerlendirme kuralları.
- **Opik Agent Optimizer**: İstemleri ve aracıları geliştirmek için özel SDK ve optimize ediciler seti.
- **Opik Guardrails**: Güvenli ve sorumlu yapay zeka uygulamalarını hayata geçirmenize yardımcı olacak özellikler.

<br>

Temel yetenekler şunları içerir:

- **Geliştirme ve İzleme:**
- Geliştirme ve üretim sırasında tüm LLM çağrılarını ve izlemelerini ayrıntılı bağlamla takip edin ([Hızlı Başlangıç](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
- Kolay gözlemlenebilirlik için kapsamlı 3. taraf entegrasyonları: En büyük ve en popüler çerçevelerin çoğunu yerel olarak destekleyen (**Google ADK**, **Autogen** ve **Flowise AI** gibi son eklemeler dahil) büyüyen çerçeve listesiyle sorunsuz bir şekilde entegre olun. ([Entegrasyonlar](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
- [Python aracılığıyla izlere ve yayılmalara geri bildirim puanlarıyla açıklama ekleyin SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) veya [Kullanıcı arayüzü](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
- [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground)'daki istemler ve modellerle denemeler yapın.

- **Değerlendirme ve Test**:
- LLM başvuru değerlendirmenizi [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) ile otomatikleştirin ve [Deneyler](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
- [Halüsinasyon tespiti](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik) gibi karmaşık görevler için güçlü Yargıç Olarak Yüksek Lisans metriklerinden yararlanın, [moderation](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) ve RAG değerlendirmesi ([Answer Uygunluk](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Bağlam Hassasiyet](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
- [PyTest entegrasyonumuzla](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik) değerlendirmeleri CI/CD ardışık düzeninize entegre edin.

- **Üretim İzleme ve Optimizasyon**:
- Yüksek hacimli üretim izlerini günlüğe kaydedin: Opik ölçek için tasarlanmıştır (günde 40 milyonun üzerinde iz).
- [Opik Kontrol Panelinden](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) geri bildirim puanlarını, iz sayılarını ve zaman içindeki jeton kullanımını izleyin.
- Üretim sorunlarını belirlemek için LLM-as-a-Judge metrikleriyle [Çevrimiçi Değerlendirme Kurallarını](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) kullanın.
- Üretimdeki LLM uygulamalarınızı sürekli olarak iyileştirmek ve güvence altına almak için **Opik Agent Optimizer** ve **Opik Guardrails**'ten yararlanın.

> [!TIP]
> Opik'in bugün sahip olmadığı özellikleri arıyorsanız lütfen yeni bir [Özellik isteği](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Opik Sunucu Kurulumu

Opik sunucunuzu dakikalar içinde çalışır hale getirin. İhtiyaçlarınıza en uygun seçeneği seçin:

### Seçenek 1: Comet.com Bulutu (En Kolay ve Önerilen)

Opik'e hiçbir kurulum yapmadan anında erişin. Hızlı başlatma ve sorunsuz bakım için idealdir.

👉 [Ücretsiz Comet hesabınızı oluşturun](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Seçenek 2: Tam Kontrol için Kendi Kendine Barındıran Opik

Opik'i kendi ortamınızda konuşlandırın. Yerel kurulumlar için Docker'ı veya ölçeklenebilirlik için Kubernetes'i seçin.

#### Docker Compose ile Kendi Kendine Barındırma (Yerel Geliştirme ve Test için)

Bu, yerel bir Opik örneğini çalıştırmanın en basit yoludur. Yeni `./opik.sh` kurulum komut dosyasına dikkat edin:

Linux veya Mac Ortamında:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

Windows Ortamında:

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Geliştirmeye Yönelik Hizmet Profilleri**

Opik kurulum komut dosyaları artık farklı geliştirme senaryolarına yönelik hizmet profillerini destekliyor:

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

Sorunları gidermek için "--help" veya "--info" seçeneklerini kullanın. Dockerfiles artık gelişmiş güvenlik için konteynerlerin root olmayan kullanıcılar olarak çalışmasını sağlıyor. Her şey hazır ve çalışır durumda olduğunda, artık tarayıcınızda [localhost:5173](http://localhost:5173) adresini ziyaret edebilirsiniz! Ayrıntılı talimatlar için [Yerel Dağıtım Kılavuzuna](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik) bakın.

#### Kubernetes ve Helm ile Kendi Kendine Barındırma (Ölçeklenebilir Dağıtımlar için)

Üretim veya daha büyük ölçekli kendi kendine barındırılan dağıtımlar için Opik, Helm grafiğimizi kullanarak bir Kubernetes kümesine kurulabilir. [Helm Kullanarak Kubernetes Kurulum Kılavuzu](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) için rozeti tıklayın.

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Sürüm 1.7.0 Değişiklikleri**: Önemli güncellemeler ve son değişiklikler için lütfen [changelog'u](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) kontrol edin.

<a id="-opik-client-sdk"></a>
## 💻 Opik İstemci SDK'sı

Opik, Opik sunucusuyla etkileşime geçmek için bir istemci kitaplıkları paketi ve bir REST API sağlar. Buna Python, TypeScript ve Ruby (OpenTelemetry aracılığıyla) için SDK'lar dahildir ve iş akışlarınızla kusursuz entegrasyona olanak tanır. Ayrıntılı API ve SDK referansları için [Opik İstemci Referans Belgelerine](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik) bakın.

### Python SDK Hızlı Başlangıç

Python SDK'yı kullanmaya başlamak için:

Paketi yükleyin:

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

Python SDK'sını, sizden Opik sunucu adresinizi (kendi kendine barındırılan örnekler için) veya API anahtarınızı ve çalışma alanınızı (Comet.com için) isteyecek olan "opik yapılandırma" komutunu çalıştırarak yapılandırın:

```bash
opik configure
```

> [!TIP]
> SDK'yı yerel olarak kendi kendine barındırılan bir kurulumda çalışacak şekilde yapılandırmak için Python kodunuzdan `opik.configure(use_local=True)` çağrısını da yapabilirsiniz veya doğrudan Comet.com için API anahtarı ve çalışma alanı ayrıntılarını sağlayabilirsiniz. Daha fazla yapılandırma seçeneği için [Python SDK belgelerine](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) bakın.

Artık [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) kullanarak izleri günlüğe kaydetmeye başlamaya hazırsınız.

<a id="-logging-traces-with-integrations"></a>
### 📝 Entegrasyonlarla İzlerin Günlüğe Kaydedilmesi

İzleri kaydetmenin en kolay yolu doğrudan entegrasyonlarımızdan birini kullanmaktır. Opik, **Google ADK**, **Autogen**, **AG2** ve **Flowise AI** gibi son eklemeler de dahil olmak üzere çok çeşitli çerçeveleri destekler:

| Entegrasyon | Açıklama | Belgeler |
| --------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ADK | Google Agent Geliştirme Kiti (ADK) için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) |
| AG2 | AG2 LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik) |
| Yapay Zeka Süiti | aisuite LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik) |
| Agno | Agno aracı düzenleme çerçevesi çağrılarına yönelik günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik) |
| Antropik | Anthropic LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik) |
| Otojen | Autogen aracılı iş akışları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik) |
| Ana kaya | Amazon Bedrock LLM çağrılarına ilişkin izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik) |
| BeeAI (Python) | BeeAI Python aracısı çerçeve çağrıları için izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik) |
| BeeAI (TypeScript) | BeeAI TypeScript aracısı çerçeve çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik) |
| BytePlus | BytePlus LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik) |
| Cloudflare Çalışanları Yapay Zeka | Cloudflare Workers AI çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Tutarlı | Cohere LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik) |
| MürettebatAI | CrewAI çağrıları için izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik) |
| İmleç | İmleç konuşmaları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik) |
| Derin Arama | DeepSeek LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik) |
| Tanımla | Dify aracısı çalıştırmaları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik) |
| DSPY | DSPy çalıştırmaları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik) |
| Havai Fişek Yapay Zeka | Fireworks AI LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik) |
| Flowise Yapay Zeka | Flowise AI görsel LLM oluşturucu için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik) |
| İkizler (Python) | Google Gemini LLM çağrılarına ilişkin izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik) |
| İkizler (TypeScript) | Google Gemini TypeScript SDK çağrılarına ilişkin izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik) |
| Büyük | Groq LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik) |
| Korkuluklar | Guardrails AI doğrulamaları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik) |
| Saman yığını | Haystack çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik) |
| Liman | Harbor kıyaslama değerlendirme denemeleri için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik) |
| eğitmen | Eğitmen ile yapılan LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik) |
| LangChain (Python) | LangChain LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) |
| LangChain (JS/TS) | LangChain JavaScript/TypeScript çağrıları için izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik) |
| LangGrafik | LangGraph yürütmeleri için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik) |
| Langflow | Langflow görsel yapay zeka oluşturucu için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik) |
| LiteLLM | LiteLLM model çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik) |
| LiveKit Temsilcileri | LiveKit Agents AI aracısı çerçeve çağrıları için izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik) |
| LlamaIndex | LlamaIndex LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) |
| Mastra | Mastra AI iş akışı çerçeve çağrıları için izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik) |
| Microsoft Agent Çerçevesi (Python) | Microsoft Agent Framework çağrılarına ilişkin izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik) |
| Microsoft Agent Çerçevesi (.NET) | Microsoft Agent Framework .NET çağrılarına ilişkin izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI | Mistral AI LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik) |
| n8n | n8n iş akışı yürütmeleri için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik) |
| Novita AI | Novita AI LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik) |
| Olma | Ollama LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik) |
| OpenAI (Python) | OpenAI LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) |
| OpenAI (JS/TS) | OpenAI JavaScript/TypeScript çağrıları için izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik) |
| OpenAI Temsilcileri | OpenAI Agents SDK çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik) |
| OpenClaw | OpenClaw ajan çalıştırmaları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter | OpenRouter LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik) |
| Açık Telemetri | OpenTelemetry destekli çağrılar için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik) |
| OpenWebUI | OpenWebUI konuşmaları için izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik) |
| Pipecat | Pipecat gerçek zamanlı sesli aracı aramaları için izlemeleri günlüğe kaydedin | [Belgeler](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik) |
| Önditaban | Predibase LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik) |
| Pydantic Yapay Zeka | PydanticAI temsilcisi çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) |
| Ragalar | Ragas değerlendirmeleri için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik) |
| Anlamsal Çekirdek | Microsoft Anlamsal Çekirdek çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik) |
| Smolajanlar | Smolagents ajanları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik) |
| Bahar AI | Spring AI çerçeve çağrılarına ilişkin izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik) |
| Strands Acenteleri | Strands acente çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik) |
| Birlikte AI | Together AI LLM çağrılarının günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik) |
| Vercel AI SDK'sı | Vercel AI SDK çağrıları için izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik) |
| VoltAjan | VoltAgent aracısı çerçeve çağrılarına ilişkin izlemeleri günlüğe kaydet | [Belgeler](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik) |
| WatsonX | IBM watsonx LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik) |
| xAI Grok | xAI Grok LLM çağrıları için günlük izlemeleri | [Belgeler](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik) |

> [!TIP]
> Kullandığınız çerçeve yukarıda listelenmiyorsa [bir sorun açmaktan](https://github.com/comet-ml/opik/issues) veya entegrasyonla ilgili bir PR göndermekten çekinmeyin.

Yukarıdaki çerçevelerden herhangi birini kullanmıyorsanız, [izleri günlüğe kaydetmek](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik) için "izleme" işlevi dekoratörünü de kullanabilirsiniz:

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> Parça dekoratörü herhangi bir entegrasyonumuzla birlikte kullanılabilir ve aynı zamanda iç içe geçmiş işlev çağrılarını izlemek için de kullanılabilir.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ Hakim Olarak Yüksek Lisans ölçümleri

Python Opik SDK'sı, LLM başvurunuzu değerlendirmenize yardımcı olacak bir dizi LLM değerlendirme ölçütü içerir. Bu konu hakkında daha fazla bilgiyi [metrik belgelerine](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik) bakarak öğrenebilirsiniz.

Bunları kullanmak için ilgili metriği içe aktarmanız ve "puan" işlevini kullanmanız yeterlidir:

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

Opik ayrıca bir dizi önceden oluşturulmuş buluşsal ölçümün yanı sıra kendi ölçümünüzü oluşturma yeteneğini de içerir. Bu konu hakkında daha fazla bilgiyi [metrik belgelerine](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik) bakarak öğrenebilirsiniz.

<a id="-evaluating-your-llm-application"></a>
### 🔍 Yüksek Lisans Başvurularınızı Değerlendirme

Opik, LLM başvurunuzu geliştirme sırasında [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) aracılığıyla değerlendirmenize olanak tanır ve [Deneyler](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). Opik Dashboard, deneyler ve büyük izlerin daha iyi işlenmesi için geliştirilmiş grafikler sunar. Ayrıca [PyTest entegrasyonumuzu](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik) kullanarak CI/CD ardışık düzeninizin bir parçası olarak değerlendirmeler çalıştırabilirsiniz.

<a id="-star-us-on-github"></a>
## ⭐ GitHub'da Bize Yıldız Verin

Opik'i yararlı buluyorsanız lütfen bize bir yıldız vermeyi düşünün! Desteğiniz topluluğumuzu büyütmemize ve ürünü geliştirmeye devam etmemize yardımcı oluyor.

[![Yıldız Geçmişi Tablosu](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 Katkıda Bulunmak

Opik'e katkıda bulunmanın birçok yolu vardır:

- [Hata raporlarını](https://github.com/comet-ml/opik/issues) ve [özellik isteklerini](https://github.com/comet-ml/opik/issues) gönderin
- Belgeleri inceleyin ve geliştirmek için [Çekme İsteklerini](https://github.com/comet-ml/opik/pulls) gönderin
- Opik hakkında konuşmak veya yazmak ve [bize bilgi vermek](https://chat.comet.com)
- Desteğinizi göstermek için [popüler özellik isteklerine](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) olumlu oy verme

Opik'e nasıl katkıda bulunacağınız hakkında daha fazla bilgi edinmek için lütfen [katkıda bulunma yönergelerimize](CONTRIBUTING.md) bakın.
