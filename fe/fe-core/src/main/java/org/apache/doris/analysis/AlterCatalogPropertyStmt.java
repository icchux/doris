// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.FeNameFormat;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.PrintableMap;
import org.apache.doris.common.util.Util;
import org.apache.doris.datasource.InternalDataSource;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.qe.ConnectContext;

import java.util.Map;

/**
 * Statement for alter the catalog property.
 */
public class AlterCatalogPropertyStmt extends DdlStmt {
    private final String catalogName;
    private final Map<String, String> newProperties;

    public AlterCatalogPropertyStmt(String catalogName, Map<String, String> newProperties) {
        this.catalogName = catalogName;
        this.newProperties = newProperties;
    }

    public String getCatalogName() {
        return catalogName;
    }

    public Map<String, String> getNewProperties() {
        return newProperties;
    }

    @Override
    public void analyze(Analyzer analyzer) throws UserException {
        super.analyze(analyzer);
        Util.checkCatalogAllRules(catalogName);
        if (!Catalog.getCurrentCatalog().getAuth().checkCtlPriv(
                ConnectContext.get(), catalogName, PrivPredicate.ALTER)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_CATALOG_ACCESS_DENIED,
                    analyzer.getQualifiedUser(), catalogName);
        }

        if (catalogName.equals(InternalDataSource.INTERNAL_DS_NAME)) {
            throw new AnalysisException("Internal catalog can't be alter.");
        }
        FeNameFormat.checkCatalogProperties(newProperties);
    }

    @Override
    public String toSql() {
        return "ALTER CATALOG " + catalogName + " SET PROPERTIES ("
                + new PrintableMap<>(newProperties, "=", true, false, ",") + ")";
    }
}
