#!/usr/bin/env node
var path = require('path');
var isWindows = path.sep === '\\';
var allureCommand = 'allure' + (isWindows ? '.bat' : '');

module.exports = function(args) {
    return require('child_process').spawn(path.join(__dirname, 'dist/bin', allureCommand), args, {
        env: process.env,
        stdio: 'inherit',
        shell: true,
    });
}
