# allure-js-commons

> Common utilities for Allure framework JavaScript integrations

[<img src="https://allurereport.org/public/img/allure-report.svg" height="85px" alt="Allure Report logo" align="right" />](https://allurereport.org "Allure Report")

- Learn more about Allure Report at https://allurereport.org
- 📚 [Documentation](https://allurereport.org/docs/) – discover official documentation for Allure Report
- ❓ [Questions and Support](https://github.com/orgs/allure-framework/discussions/categories/questions-support) – get help from the team and community
- 📢 [Official annoucements](https://github.com/orgs/allure-framework/discussions/categories/announcements) – be in touch with the latest updates
- 💬 [General Discussion ](https://github.com/orgs/allure-framework/discussions/categories/general-discussion) – engage in casual conversations, share insights and ideas with the community

---

Interface for Allure to be used from Javascript and TypeScript.
There you can find primitives to create custom integrations for the javascript testing frameworks.

## API Overview

### Labels environment variables

Allure allows to use environment variables for setting test labels.
Using `ALLURE_LABEL_{{labelName}}={{labelValue}}` syntax you can set common labels for all of your tests.

#### Examples

```bash
ALLURE_LABEL_epic="Story 1" npm test
```
