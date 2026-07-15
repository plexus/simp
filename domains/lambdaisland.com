[lambdaisland.com]
A="185.199.108.153"
A="185.199.109.153"
A="185.199.110.153"
A="185.199.111.153"
MX="mx1.mailchannels.net" priority=10
MX="mx2.mailchannels.net" priority=20
TXT="clojars plexus"

[_acme-challenge.lambdaisland.com]
CNAME="_acme-challenge.prod.acme-challenges.lambdaisland.com" ttl=300

[_acme-challenge.forecastle.lambdaisland.com]
CNAME="_acme-challenge.prod.acme-challenges.lambdaisland.com" ttl=300

[_acme-challenge.schooner.lambdaisland.com]
CNAME="_acme-challenge.schooner.acme-challenges.lambdaisland.com" ttl=300

[_acme-challenge.www.lambdaisland.com]
CNAME="_acme-challenge.prod.acme-challenges.lambdaisland.com" ttl=300

[acme-challenges.lambdaisland.com]
NS="ns-cloud-e1.googledomains.com" ttl=300
NS="ns-cloud-e2.googledomains.com" ttl=300
NS="ns-cloud-e3.googledomains.com" ttl=300
NS="ns-cloud-e4.googledomains.com" ttl=300

[email.mailgun.lambdaisland.com]
CNAME="mailgun.org"

[forecastle.lambdaisland.com]
A="194.182.180.64" ttl=300

[img.lambdaisland.com]
A="69.163.157.97"
NS="ns1.dreamhost.com"
NS="ns2.dreamhost.com"
NS="ns3.dreamhost.com"

[mailboxes.lambdaisland.com]
A="66.33.205.233"

[mailgun.lambdaisland.com]
MX="mx1.mailchannels.net" priority=10
MX="mx2.mailchannels.net" priority=20
TXT="v=spf1 include:mailgun.org ~all"

[notebooks.lambdaisland.com]
A="35.190.54.14" ttl=600

[schooner.lambdaisland.com]
A="194.182.183.51" ttl=300

[smtp._domainkey.mailgun.lambdaisland.com]
TXT="k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCwOypxFn7JLkppNktrZ3caiIv1s9X2ezS0m0Q8MOXOdFmbd9oVjJucCZg0/fF2ltx0pso5odq+hoRiaFM4u3hDzFHLHiwaeaw3e3EZ2+OSZ9O3f2V8d3d7yHnVDOTaf1LHBgxcpXOMV9sqpArMn3k7RQakESaC0jxbsxClvlzSZwIDAQAB"

[web1.lambdaisland.com]
A="94.237.24.185" ttl=600

[web2.lambdaisland.com]
A="94.237.26.52"

[webmail.lambdaisland.com]
A="208.97.187.193"

[witchcraft.lambdaisland.com]
A="94.237.45.83" ttl=60

[www.lambdaisland.com]
CNAME="lambdaisland.github.io" ttl=60


# Local Variables:
# mode:conf
# End:
