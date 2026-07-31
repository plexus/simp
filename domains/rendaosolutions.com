[simp]
account="dreamhost"

[rendaosolutions.com]
MX="0 mx1.mailchannels.net."
MX="0 mx2.mailchannels.net."
TXT="v=spf1 mx include:netblocks.dreamhost.com include:relay.mailchannels.net -all"

[_autodiscover._tcp.rendaosolutions.com]
SRV="5 0 443 autoconfig.dreamhost.com"

[auberginn.rendaosolutions.com]
A="64.90.52.223"

[autoconfig.rendaosolutions.com]
CNAME="autoconfig.dreamhost.com"

[blacailloux.rendaosolutions.com]
A="64.90.52.223"

[domymove.rendaosolutions.com]
A="64.90.52.223"

[dreamhost._domainkey.rendaosolutions.com]
TXT="v=DKIM1; k=rsa; h=sha256;  p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA6HaTKlM+0MP3C/84QYMqtVNFntiosEvDS0n44GZjR1ZoaGzxCAbcX1QO+7pdkm2sX9ti8koPNe2pxjXjC9ZaSxaX+FVT9qk8ShebCJgINei+kk2FAdvxsW0ZCZcOS5Sqsv64OTHgmPhLd4T/d4ZSGaUA/LK1Ry2SvjCtKpAKwGZaUe1xuZLVps3NAZoLizaCJ1xnqePvSgXQq33sXWZB1eauBCnFRqJWz5WApMmfX/CVUaVR2HfLb8IMSnCHDIQ98xXiVI+bIx2BpGG4x14mKFECGfk+Sp/ubEobYp5Nc8DQbC+7JNm2abbm+90PVdbh2ad4C5v6Tov70X04pF6nTwIDAQAB"

[ftp.auberginn.rendaosolutions.com]
A="64.90.52.223"

[ftp.blacailloux.rendaosolutions.com]
A="64.90.52.223"

[ftp.domymove.rendaosolutions.com]
A="64.90.52.223"

[ftp.simpleinvoices.rendaosolutions.com]
A="64.90.52.223"

[ftp.wiki.rendaosolutions.com]
A="64.90.52.223"

[mail.rendaosolutions.com]
A="64.90.62.162"
MX="0 mx1.mailchannels.net."
MX="0 mx2.mailchannels.net."

[mailboxes.rendaosolutions.com]
A="69.163.136.97"

[mysql.rendaosolutions.com]
A="64.90.32.100"

[openerp.rendaosolutions.com]
A="178.62.151.239"

[simpleinvoices.rendaosolutions.com]
A="64.90.52.223"

[ssh.auberginn.rendaosolutions.com]
A="64.90.52.223"

[ssh.blacailloux.rendaosolutions.com]
A="64.90.52.223"

[ssh.domymove.rendaosolutions.com]
A="64.90.52.223"

[ssh.simpleinvoices.rendaosolutions.com]
A="64.90.52.223"

[ssh.wiki.rendaosolutions.com]
A="64.90.52.223"

[webmail.rendaosolutions.com]
A="69.163.136.138"

[wiki.rendaosolutions.com]
A="64.90.52.223"

[www.auberginn.rendaosolutions.com]
A="64.90.52.223"

[www.blacailloux.rendaosolutions.com]
A="64.90.52.223"

[www.domymove.rendaosolutions.com]
A="64.90.52.223"

[www.mailboxes.rendaosolutions.com]
A="69.163.136.97"

[www.simpleinvoices.rendaosolutions.com]
A="64.90.52.223"

[www.webmail.rendaosolutions.com]
A="69.163.136.138"

[www.wiki.rendaosolutions.com]
A="64.90.52.223"


# Local Variables:
# mode:conf
# End:
