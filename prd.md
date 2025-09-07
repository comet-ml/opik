### **TL;DR**

Our goal is to make it simple for SMEs to review and annotate agent outputs in Opik with **easy queues, invitations, and a clean annotation UI**.

****

## **Problem statement**

In the lifecycle of deploying AI agents—both **before full-scale release** and **post-deployment**—subject matter experts (SMEs) play a critical role in validating and improving agent performance. Their expertise is essential for reviewing real user interactions, identifying issues, and providing targeted feedback to refine the agent’s behavior.

Today, in Opik, while human feedback is supported, the process is **not optimized for SME participation**:

- **Non-technical users** find the interface and workflow unintuitive and difficult to navigate.
- **Technical teams** struggle to incorporate SME feedback efficiently into iterative improvement cycles.
- The lack of structured review processes results in **scattered feedback**, **low annotation throughput**, and **missed opportunities** to identify error patterns.

This friction slows down the feedback loop, reduces annotation quality, and ultimately delays agent improvements.

### **Goal**

Enable customers to **seamlessly involve SMEs and team members** in the feedback process—making human annotation a natural, scalable, and actionable part of agent development and improvement.

The epic aims to **equip teams with tools** that streamline:

- **Collaboration:** Simple mechanisms for inviting SMEs to review and annotate content.
- **Annotation Management:** Easy creation and management of annotation queues for traces, and threads.
- **Feedback Quality:** Intuitive, friendly annotation interfaces designed for non-technical contributors.
- **Analysis & Actionability:** Tools to help technical teams analyze issues, recognize recurring error patterns, and feed these insights directly into agents by retraining or refining prompts.

## **Market analysis: is there a real market need for this?**

Yes, there is growing amount of evidence about it: 

- **Overall data annotation tools market growth:** Forecasted CAGR of **26–26.4% from 2024 to 2032**. [Source](https://linkewire.com/2025/02/07/data-annotation-tools-market/?utm_source=chatgpt.com)
- **Human-in-the-loop remains essential:** Critical for sensitive domains like healthcare and finance. [Source](https://mindy-support.com/news-post/data-annotation-in-2025-emerging-trends-and-future/?utm_source=chatgpt.com)
- **AI-assisted annotation tools market growth:** Expected to grow from **US $1.4 billion in 2023 to US $7.8 billion by 2033** (CAGR 19.6%). [Source](https://datahorizzonresearch.com/ai-assisted-annotation-tools-market-38911?utm_source=chatgpt.com)
- **Industry survey on challenges:** 86% cite subjectivity/inconsistency in annotation; 65% stress the need for domain experts. [Source](https://imerit.net/resources/blog/why-human-feedback-in-ai-model-tuning-still-matters-comparing-metas-sam-to-purpose-built-models-for-specialized-use-cases/?utm_source=chatgpt.com)
- **Annotation costs surging:** Up to **300% increase from 2023 to 2025**, with organizations spending **$15–30 million annually** on human feedback for AI training. [Source](https://markaicode.com/ai-agent-rlhf-annotation-cost-crisis-2025/?utm_source=chatgpt.com)
- **High demand for expert annotation services:** Turing’s revenue surged to **$300 million**, serving clients like OpenAI, Google, and Meta. [Source](https://www.reuters.com/technology/artificial-intelligence/ai-data-startup-turing-triples-revenue-300-million-2025-01-28/?utm_source=chatgpt.com)
- **Strategic investments in annotation:** Meta invested in Scale AI to secure high-quality human annotation for model development. [Source](https://www.techradar.com/pro/why-did-meta-invest-in-scale-ai-and-how-will-it-change-the-ai-models-you-use?utm_source=chatgpt.com)

## **Areas of Solution Exploration**

Area 1: human feedback collection

1. **Annotation Queue Setup**
    - Easy creation of annotation queues from selected traces, or threads.
2. **SME Invitation & access Management**
    - Simple invitation flows for SMEs—no complex onboarding.
    - Role-based permissions to ensure SMEs have the right level of access without exposing sensitive configuration.
3. **User-Friendly & Customizable Annotation Experience**
    - Rich annotation UI with inline comment tools, tagging systems, and customizable annotation schemas.
    - Distraction-less annotation experience
    - Customizable annotation UIs

Area 2: human feedback integration - closing the loop

1. **Error Pattern Recognition**
    - Automated clustering of similar issues or behaviors.
    - Insights into common failure modes and areas of improvement.
2. **Seamless Feedback Integration - closing the loop**
    - Direct integration from annotated content into agent updates.
    - Version tracking to measure improvement impact over time.
3. **Automatic agent optimization based on human feedback** 
    - Use human expert feedback as few-shot examples to improve the agent
    - Automatically Generate evals (metrics) based on learnings Generate new versions of the

**[IMPORTANT Note: we are leaving out the second area of work so that we focus on human annotation first and then close the loop in the next phase]**

## **Competitor analysis**

Most competitors already offer similar capabilities:

- https://arize.com/docs/ax/evaluate/human-annotations#labeling-queues
- https://langfuse.com/changelog/2024-10-10-annotation-queues
- https://docs.smith.langchain.com/evaluation/how_to_guides/annotation_queues

Competitor Product Demos:

- LangSmith demo: https://www.youtube.com/watch?v=rxKYHA-2KS0&ab_channel=LangChain
- Arize demo: https://www.youtube.com/watch?v=20U6INQJyyU&ab_channel=ArizeAI
- Galileo annotation demo: https://www.youtube.com/watch?v=HtiKOUhVM1A&ab_channel=Galileo
- LangChain demo: https://www.youtube.com/watch?v=3Ws5wOS9eko&t=36s&ab_channel=HelveteTV
- Langsmith demo: https://www.youtube.com/watch?v=jP24Z5Raje4&ab_channel=LangChain
- LiteralAI demo: https://www.loom.com/share/024950f9e2aa4ffa98e4b0842137c986?sid=5d196de1-e5a5-47b9-8919-60102951cd48
- 

There are other specialized tools for human feedback collection that offer better experiences 

- https://www.youtube.com/watch?v=FlJ6hrBB2bU&ab_channel=Argilla

Reflection

While many competitors—such as LangSmith, Arize and Langfuse— support human annotation workflows, the user experience is often unintuitive and not very user-friendly for non-technical users. 

As a result, some customers resort to building their own bespoke interfaces to meet their specific needs, while others fall back on outdated, error-prone methods like passing around messy spreadsheets via email. This reflects a broader industry gap: the capability exists, but it’s far from being solved in a streamlined, scalable, and user-friendly way.

## **Features & Capabilities by Persona**

There are three user personas involved in this EPIC: project admin, Subject Matter Expert (SME) and AI developer. 

- Project admin: responsible for the annotation experience setup, SME onboarding, and defining the annotation experience.
- SME: responsible for reviewing and annotating traces/threads.
- AI developer: responsible for analyzing feedback and improving the agent. We are not going to prioritize this persona at this point.

### **1. Project Admin**

### **Annotation Queue Management**

- Create, update, delete annotation queues.
- Define queues based on:
    - **Filters**: by date range, agent version, conversation type (trace vs. thread), user segment, or scenario tag.
    - **Performance metrics**: low-confidence outputs, low user satisfaction scores, high error rates.
    - **Random sampling**: for unbiased review.
- Each queue can be assigned to one or several SMEs.
- Traces and threads can be added to new or existing annotation queues. Manually, one by one, or in bulk.

### **SME Access & Invitation**

- Invite SMEs via:
    - Email with auto-generated one-click access link.
    - Shareable secure link.
- Role-based permissions:
    - **Annotate-only** (no access to system settings).

### **Custom Annotation UI Configuration**

- Display pretty mode by default
- Ability to Create custom annotation displays (templates) where he can define **data fields** visible to SMEs:
    - Eg: Prompt, response, metadata, conversation history, date, user input, intermediate steps, etc
- Configure **annotation** settings:
    - Select the metrics that the SME is going to see. With definitions/explanations.
    - Comment field (required/optional).
    - Tag field (required/optional).
- UX: Context-rich onboarding screens and inline guidance.

---

### **2. SME (Subject Matter Expert)**

### **Onboarding & Guidance**

- Receive **invitation email** with:
    - Clear task description and goals.
    - Timeline and expected throughput.
- Contextual help:
    - Embedded tooltips (short how-to video?)
    - Quick tips popover in UI.
- Can also receive a link via slack/WhatsApp without much context. After clicking she should be able to log in and start annotating.

### **Annotation Experience**

- Minimal-distraction interface:
    - Focus mode (just the text + annotation controls).
    - Optional not visually priortariy expand for full advanced conversation/metadata/data/trace/thread context.
- Quick navigation between items (keyboard shortcuts, “next/previous”).
- Clear indication on how many items there are in the queue and how many are left to finish.
- Remembers where the user left if he abandoned the experience.
- Customizable display:
    - Hide/show metadata.
    - Adjustable font size.

---

# Epic milestone phasing and user story breakdown

## Milestone #1 – Shareable Annotation Queues

Description
Enable project admins to create simple annotation queues and share them via unique links. SMEs can access and annotate only the items in their assigned queue. This milestone delivers the minimum functional loop: create queue → share → annotate.

**User Stories**

Admin
• As a Project Admin, I want to create an annotation queue.
• As a Project Admin, I want to add traces/threads to a new or existing queue manually or in bulk so that I can assign relevant examples for review.
• As a Project Admin, I want to use a unique queue link/URL so that I can share it securely with SMEs.

SME
• As an SME, when I click a queue link, I will sign up/sign in quickly (email, SSO,.. ) so that I can access queue items.
• As an SME, when I land in the queue view, I want to see only the traces or threads assigned in that queue so that I stay focused on my annotation tasks.

## Milestone #2 – Simplified Human Annotation Experience

Description
Deliver a streamlined annotation experience tailored to SMEs. SMEs get a distraction-free, progress-aware annotation interface.

**User Stories**

SME

• As an SME, I want a simple, distraction-free annotation UI (input, output, minimal metadata) so that I can review effectively without technical clutter.
• As an SME, I want a progress indicator so that I know how much I’ve completed and what’s still pending.
• As an SME, I want to toggle metadata visibility (e.g., hide/expand metadata, or full trace content if needed) so that I can balance speed with depth.

• As an SME, I want to see project details, task summary, and expected effort so that I know what’s expected before starting.

## Milestone #3 - Invitations & Progress Tracking

Description
Extend the annotation flow with invitation management and progress tracking, enabling admins to provide SMEs with clear onboarding instructions and visibility into their throughput.

Admin
• As a Project Admin, I want to send invitation emails directly from Opik so that SMEs receive clear instructions and don’t rely only on raw links.
• As a Project Admin, I want a queue dashboard showing per-queue and per-SME progress so that I can manage throughput and identify bottlenecks.

SME
• As an SME, when I receive an invitation email

# Functional requirements - UX Discussion

## 1) Feature List by Phase

### v0.1 (MVP – shareable queue link + basic annotation)

1. **Queues CRUD (minimal)**
    - Create queue (name, description) → generates unique **Queue Link/URL**.
    - Add Traces/Threads to queue (manual, single & bulk) from Traces/Threads list/detail.
    - Shortcut: CTA to "Add items" that get you to the traces and threads section
    - Queue overview stats (counts by state: unstarted/completed).
2. **SME Access (auth required)**
    - Hitting Queue Link → Sign-up/Sign-in (email, password, or SSO) → lands in **SME Annotation View**.
3. **SME Annotation UI (default schema)**
    - Pretty read view of item (input output, timestamp, minimal metadata).
    - Metrics. Examples: **Rating** (1–5), **Thumbs up/down**, **Categories (multi-select)**
    - Comment input (free text).
    - Progress indicator (e.g., “17 of 120”).
    - IMPORTANT: no navigation escape to broader Opik product.

### 

### v0.2 (Invites + configurable metrics/fields)

1. **Invite Flow**
    - Admin can add SME emails → system sends invite email with task summary.
2. **Annotation Experience Configuration**
    - Admin chooses **visible fields** for SME (prompt, response, metadata fields, history, etc.).
    - Admin selects **metrics,** sets **required/optional**.
3. **Queue Dashboard**
    - Per-queue progress, per-SME progress, item state breakdown.

### 

### v0.3 (Custom templates)

1. **Annotation Templates**
    - Create named templates for reuse; versioned; assign to queues.
    - Templates support: field visibility, instructions/help copy, metrics layout, required fields, tag taxonomies.
2. **Light Export & APIs and notifications**
    - CSV/JSON export of human annotations.
    - REST/SDK to create queues, get url, add items, check status.
    - Notification when a queue is completed.

---

## 

---

## 3) UX Requirements by Screen/Flow

### 3.1 Queues List (Admin)

- **Empty state:** CTA: “Create your first annotation queue”.
- **List columns:** Name, Description, Status (total/remaining), Assigned SMEs, Template, Last updated.
- **Actions:** Create, Delete (with confirm), View, Update, Add Items, Share, Invite SMEs.
- **Search & filter:** by status (active/completed), type (Trace/Thread), template, date range.

### 3.2 Create Queue (v0.1 minimal)

- **Fields:** Name (required), Description (optional).
- **On success:** Show Queue Link (copy button) and Add Items dialog.

**v0.2 additions:** template selection (optional), instructions text, SLA date, invite SMEs.

### 3.3 Add Items to Queue

- From Traces/Threads list/detail: **Add to Queue** → modal (select existing queue or “New queue”).
- **Bulk add:** respects existing search filters; show confirmation with counts & duplicates skipped.
- [Study viability] Random sampling: make a random selection of XXX traces to add to the queue. If you have 10000 traces, the system selects a random selection of 100 for your SME. This reduces bias in the evaluation process.

### 3.4 Invite SMEs (v0.2)

- **Inputs:** emails (comma-separated), optional message, due date, expected throughput.
- **State:** pending/accepted/expired; actions: resend, revoke.
- **Email content:** project name, queue name, task summary, expected effort, CTA button.

### 3.5 Templates (v0.3)

- **CRUD:** create, duplicate, version, archive.
- **Designer:** drag-and-drop field visibility & order; validation rules; help text; tag taxonomies.
- **Preview:** left = schema, right = live preview with a sample item.

### 3.6 SME Annotation View

- **Header:** queue name, progress status.
- **Main:** item content (pretty mode), toggle “Show metadata”.
- **Submit:** validates required fields; auto-advance to next item; snackbar “Saved”.
- **Progress:** e.g., “17 / 120 completed”.
- **No escape:** no app nav except Leave (confirm dialog).

## Open questions and features to think about

- **Email notifications** — we need email notification support to notify SMEs of new tasks, new items in their queue; notify project admin when queues complete, manual notifications from the SMEs to the team (different from commenting, just to flag issues, or to say there are going to be delays, or that you are ready for more, etc). How can we support email notifications in cloud, on-prem and open-source versions of Opik?
- **SDK/REST API Access** — Allow programatic usage of the feature for creating annotation queues and adding traces to a concrete queue via the REST API or SDK. This can be relevant when automating HITL integrated in application development workflows, CI/CD pipelines, etc.
- **Permissions & Security** — Ensure PII masking and controlled data visibility. Already asked by some customers, might need to control what personal information we display? Do we need to limit the permissions of the SMEs? Do we need them to sign up and have an account or can this be done anonymously? Hypothesis: For auditing purposes, SMEs should have an account, so easy signup should be part of the journey.
- **Data visibility**: the SMEs should NOT be able to see other SME’s annotations not to be biased.
- Should the SME be able to navigate to the full list of traces/threads in the product in some scenarios? Or is that 100% blocked? Think about it.
- **Mobile support**: if it’s possible/cheap let’s explore ways for SMEs to annotate on the go. This won’t be the top scenario, but might be helpful.

