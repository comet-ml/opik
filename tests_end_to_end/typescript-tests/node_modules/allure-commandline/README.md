# Allure Commandline 

![downloads](https://img.shields.io/npm/dm/allure-commandline.svg?style=flat-square) [![npm version](https://img.shields.io/npm/v/allure-commandline.svg?style=flat-square)](https://www.npmjs.com/package/allure-commandline)

> NPM wrapper for Allure Commandline

<img src="https://allurereport.org/public/img/allure-report.svg" height="85px" alt="Allure Report logo" align="right" />

- Learn more about Allure Report at [https://allurereport.org](https://allurereport.org)
- 📚 [Documentation](https://allurereport.org/docs/) – discover official documentation for Allure Report
- ❓ [Questions and Support](https://github.com/orgs/allure-framework/discussions/categories/questions-support) – get help from the team and community
- 📢 [Official announcements](https://github.com/orgs/allure-framework/discussions/categories/announcements) –  stay updated with our latest news and updates
- 💬 [General Discussion](https://github.com/orgs/allure-framework/discussions/categories/general-discussion) – engage in casual conversations, share insights and ideas with the community
- 🖥️ [Live Demo](https://demo.allurereport.org/) — explore a live example of Allure Report in action

## Install

1. Allure requires [Java 8](https://bell-sw.com/pages/downloads/#jdk-17-lts) or higher
2. `npm install -g allure-commandline --save-dev`

## Usage

```
allure <command> [<args>]
```
Run `allure help` for a list of supported commands.

## Node.js API

You can also call Allure commands from your Node.js code:

```js
var allure = require('allure-commandline');

// returns ChildProcess instance
var generation = allure(['generate', 'allure-results']);

generation.on('exit', function(exitCode) {
    console.log('Generation is finished with code:', exitCode);
});
```

# Development

Allure packages should be downloaded from external storage. Repository content doesn't have actual code.

1. Update package version `$ npm version 2.13.0`
1. Download the Allure-commandline package: `./fetch-source`
1. Publish result to NPM: `npm publish`

