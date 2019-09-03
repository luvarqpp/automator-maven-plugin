git init
git add README.md
git commit -m "first commit"
git remote add origin https://github.com/sammartens1234/automator-maven-plugin.git
git push -u origin master


TODO deploy to OSS repository

mvn deploy


GPP:


gpg --gen-key
gpg --list-keys
gpg --list-secret-keys
gpg --keyserver hkp://pool.sks-keyservers.net --send-keys 537F0921




Realm: Jamo Solutions
email: info@jamosolutions.com

TODO:
* OnlineLogTestRunExecReport improvements:
** logSummaryReport should print info about retries per device
** logProgressReport could print somehow info about which devices are idle and how long queues does have other devices