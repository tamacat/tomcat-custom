<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<html><body>
<p id="scriptlet"><%= "JSP-OK" %></p>
<p id="el">EL=${1 + 2}</p>
<p id="param">PARAM=${param.name}</p>
<p id="lambda">LAMBDA=${((x) -> x * 10)(4)}</p>
</body></html>
