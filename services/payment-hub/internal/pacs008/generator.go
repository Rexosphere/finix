package pacs008

import (
	"encoding/xml"
	"fmt"
	"strings"
	"time"

	"github.com/rexosphere/finix/payment-hub/internal/domain"
)

// Document is a simplified ISO 20022 pacs.008.001.08 credit-transfer message.
type Document struct {
	XMLName xml.Name                     `xml:"Document"`
	Xmlns   string                       `xml:"xmlns,attr"`
	FIToFI  FIToFICustomerCreditTransfer `xml:"FIToFICstmrCdtTrf"`
}

type FIToFICustomerCreditTransfer struct {
	GrpHdr GroupHeader      `xml:"GrpHdr"`
	CdtTrf CreditTransferTx `xml:"CdtTrfTxInf"`
}

type GroupHeader struct {
	MsgID   string         `xml:"MsgId"`
	CreDtTm string         `xml:"CreDtTm"`
	NbOfTxs string         `xml:"NbOfTxs"`
	Sttlm   SettlementInfo `xml:"SttlmInf"`
}

type SettlementInfo struct {
	SttlmMtd string `xml:"SttlmMtd"`
}

type CreditTransferTx struct {
	PmtID    PaymentIdentification `xml:"PmtId"`
	IntrBk   Amount                `xml:"IntrBkSttlmAmt"`
	ChrgBr   string                `xml:"ChrgBr"`
	Dbtr     Party                 `xml:"Dbtr"`
	DbtrAcct Account               `xml:"DbtrAcct"`
	CdtrAgt  Agent                 `xml:"CdtrAgt"`
	Cdtr     Party                 `xml:"Cdtr"`
	CdtrAcct Account               `xml:"CdtrAcct"`
}

type PaymentIdentification struct {
	EndToEndID string `xml:"EndToEndId"`
	TxID       string `xml:"TxId"`
}

type Amount struct {
	Ccy   string `xml:"Ccy,attr"`
	Value string `xml:",chardata"`
}

type Party struct {
	Nm string `xml:"Nm"`
}

type Account struct {
	ID AccountID `xml:"Id"`
}

type AccountID struct {
	Othr OtherAccount `xml:"Othr"`
}

type OtherAccount struct {
	ID string `xml:"Id"`
}

type Agent struct {
	FinInstnID FinancialInstitution `xml:"FinInstnId"`
}

type FinancialInstitution struct {
	Nm string `xml:"Nm"`
}

// Generate builds a simplified pacs.008 XML document for the given payment.
func Generate(p domain.Payment) ([]byte, error) {
	major := fmt.Sprintf("%.2f", float64(p.AmountMinor)/100.0)
	doc := Document{
		Xmlns: "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08",
		FIToFI: FIToFICustomerCreditTransfer{
			GrpHdr: GroupHeader{
				MsgID:   "MSG-" + p.ID,
				CreDtTm: p.CreatedAt.UTC().Format(time.RFC3339),
				NbOfTxs: "1",
				Sttlm:   SettlementInfo{SttlmMtd: "CLRG"},
			},
			CdtTrf: CreditTransferTx{
				PmtID: PaymentIdentification{
					EndToEndID: p.EndToEndId,
					TxID:       p.ID,
				},
				IntrBk:   Amount{Ccy: p.Currency, Value: major},
				ChrgBr:   "SLEV",
				Dbtr:     Party{Nm: "FINIX Debtor"},
				DbtrAcct: Account{ID: AccountID{Othr: OtherAccount{ID: p.DebtorAccount}}},
				CdtrAgt:  Agent{FinInstnID: FinancialInstitution{Nm: schemeAgent(p.Scheme)}},
				Cdtr:     Party{Nm: "FINIX Creditor"},
				CdtrAcct: Account{ID: AccountID{Othr: OtherAccount{ID: p.CreditorAccount}}},
			},
		},
	}

	out, err := xml.MarshalIndent(doc, "", "  ")
	if err != nil {
		return nil, err
	}
	return append([]byte(xml.Header), out...), nil
}

func schemeAgent(s domain.Scheme) string {
	switch s {
	case domain.SchemeLankaPay:
		return "LankaPay Clearing"
	case domain.SchemeVisa:
		return "Visa Direct"
	case domain.SchemeCBDC:
		return "CBSL CBDC Rail"
	default:
		return strings.ToUpper(string(s))
	}
}
